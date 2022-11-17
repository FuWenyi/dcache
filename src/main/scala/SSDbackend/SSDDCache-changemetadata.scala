/**************************************************************************************
 * Copyright (c) 2020 Institute of Computing Technology, CAS
 * Copyright (c) 2020 University of Chinese Academy of Sciences
 *
 * NutShell is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *             http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
 * FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

package SSDbackend

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.simplebus._
import bus.axi4._
import chisel3.experimental.IO
import com.google.protobuf.Internal.FloatList
import utils._
import top.Settings
import nutcore._

import freechips.rocketchip.tilelink_
import freechips.rocketchip.diplomacy.{IdRange, LazyModule, LazyModuleImp, TransferSizes}
import freechips.rocketchip.tilelink.ClientMetadata
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.rocket.constants.MemoryOpConstants

case class SSDCacheConfig (
                         ro: Boolean = false,
                         name: String = "cache",
                         userBits: Int = 0,
                         idBits: Int = 0,

                         totalSize: Int = 16, // Kbytes
                         ways: Int = 4,
                         srcBits: Int = 1
                       )

sealed trait HasCacheConst {
  implicit val cacheConfig: SSDCacheConfig

  val PAddrBits: Int
  val XLEN: Int

  val cacheName = cacheConfig.name
  val userBits = cacheConfig.userBits
  val idBits = cacheConfig.idBits

  val TotalSize = cacheConfig.totalSize
  val Ways = cacheConfig.ways
  val LineSize = XLEN // byte
  val LineBeats = LineSize / 8 //DATA W64IDTH 
  val Sets = TotalSize * 1024 / LineSize / Ways
  val OffsetBits = log2Up(LineSize)
  val IndexBits = log2Up(Sets)
  val WordIndexBits = log2Up(LineBeats)
  val TagBits = PAddrBits - OffsetBits - IndexBits

  def addrBundle = new Bundle {
    val tag = UInt(TagBits.W)
    val index = UInt(IndexBits.W)
    val wordIndex = UInt(WordIndexBits.W)
    val byteOffset = UInt((if (XLEN == 64) 3 else 2).W)
  }

  def CacheMetaArrayReadBus() = new SRAMReadBus(new MetaBundle, set = Sets, way = Ways)
  def CacheTagArrayReadBus() = new SRAMReadBus(new TagBundle, set = Sets, way = Ways)
  def CacheDataArrayReadBus() = new SRAMReadBus(new DataBundle, set = Sets * LineBeats, way = Ways)
  def CacheMetaArrayWriteBus() = new SRAMWriteBus(new MetaBundle, set = Sets, way = Ways)
  def CacheTagArrayWriteBus() = new SRAMWriteBus(new MetaBundle, set = Sets, way = Ways)
  def CacheDataArrayWriteBus() = new SRAMWriteBus(new DataBundle, set = Sets * LineBeats, way = Ways)

  def getMetaIdx(addr: UInt) = addr.asTypeOf(addrBundle).index
  def getDataIdx(addr: UInt) = Cat(addr.asTypeOf(addrBundle).index, addr.asTypeOf(addrBundle).wordIndex)

  def isSameWord(a1: UInt, a2: UInt) = ((a1 >> 2) == (a2 >> 2))
  def isSetConflict(a1: UInt, a2: UInt) = (a1.asTypeOf(addrBundle).index === a2.asTypeOf(addrBundle).index)
}

sealed abstract class CacheBundle(implicit cacheConfig: SSDCacheConfig) extends Bundle with HasNutCoreParameter with HasCacheConst
sealed abstract class CacheModule(implicit cacheConfig: SSDCacheConfig) extends Module with HasNutCoreParameter with HasCacheConst with HasNutCoreLog

sealed class TagBundle(implicit val cacheConfig: SSDCacheConfig) extends CacheBundle {
  val tag = Output(UInt(TagBits.W))

  def apply(tag: UInt) = {
    this.tag := tag
    this
  }
}

sealed class MetaBundle(implicit val cacheConfig: SSDCacheConfig) extends CacheBundle {
  val coh = new ClientMetadata

  def apply(coh: UInt) = {
    this.coh := coh.asTypeOf(new ClientMetadata)
    this
  }
}

sealed class DataBundle(implicit val cacheConfig: SSDCacheConfig) extends CacheBundle {
  val data = Output(UInt(DataBits.W))

  def apply(data: UInt) = {
    this.data := data
    this
  }
}

class SSDCacheIO(implicit val cacheConfig: SSDCacheConfig) extends Bundle with HasNutCoreParameter with HasCacheConst {
  val in = Flipped(new SimpleBusUC(userBits = userBits, idBits = idBits))
  val flush = Input(Bool())
  //val out = new SimpleBusC
  val mmio = new SimpleBusUC
}

trait HasSSDCacheIO {
  implicit val cacheConfig: SSDCacheConfig
  val io = IO(new SSDCacheIO)
}

sealed class SSDStage1IO(implicit val cacheConfig: SSDCacheConfig) extends CacheBundle {
  val req = new SimpleBusReqBundle(userBits = userBits, idBits = idBits)
  val mmio = Output(Bool())
}
// meta read
sealed class SSDCacheStage1(implicit val cacheConfig: SSDCacheConfig) extends CacheModule {
  class SSDCacheStage1IO extends Bundle {
    val in = Flipped(Decoupled(new SimpleBusReqBundle(userBits = userBits, idBits = idBits)))
    val out = Decoupled(new SSDStage1IO)
    val metaReadBus = CacheMetaArrayReadBus()
    val dataReadBus = CacheDataArrayReadBus()
    val tagReadBus = CacheTagArrayReadBus()
  }
  val io = IO(new SSDCacheStage1IO)

  val new_cmd = LookupTree(io.in.bits.cmd, List(
    SimpleBusCmd.read   -> MemoryOpConstants.M_XRD,   //int load             
    SimpleBusCmd.write  -> MemoryOpConstants.M_XWR    //int store
  ))

  // read meta array, tag array and data array
  val readBusValid = io.in.fire()
  io.metaReadBus.apply(valid = readBusValid, setIdx = getMetaIdx(io.in.bits.addr))
  io.tagReadBus.apply(valid = readBusValid, setIdx = getMetaIdx(io.in.bits.addr))
  io.dataReadBus.apply(valid = readBusValid, setIdx = getDataIdx(io.in.bits.addr))

  //metaArray need to reset before Load
  //s1 is not ready when metaArray is resetting or meta/dataArray is being written

  if(cacheName == "dcache") {
    val s1NotReady = (!io.metaReadBus.req.ready || !io.dataReadBus.req.ready || !io.metaReadBus.req.ready || !io.tagReadBus.req.ready)&& io.in.valid
    BoringUtils.addSource(s1NotReady,"s1NotReady")
  }

  io.out.bits.req := io.in.bits
  io.out.bits.req.cmd := new_cmd
  io.out.valid := io.in.valid && io.metaReadBus.req.ready && io.dataReadBus.req.ready && io.tagReadBus.req.ready
  io.in.ready := io.out.ready && io.metaReadBus.req.ready && io.dataReadBus.req.ready && io.tagReadBus.req.ready
  io.out.bits.mmio := AddressSpace.isMMIO(io.in.bits.addr)
}


// check
sealed class SSDCacheStage2(implicit val cacheConfig: SSDCacheConfig)(edge: TLEdgeOut) extends CacheModule {
  class SSDCacheStage2IO(edge: TLEdgeOut) extends Bundle {
    val in = Flipped(Decoupled(new SSDStage1IO))
    val out = Decoupled(new SimpleBusRespBundle(userBits = userBits, idBits = idBits))
    val flush = Input(Bool())
    val metaReadResp = Flipped(Vec(Ways, new MetaBundle))
    val tagReadResp = Flipped(Vec(Ways, new TagBundle))
    val dataReadResp = Flipped(Vec(Ways, new DataBundle))

    val dataReadBus = CacheDataArrayReadBus()
    val metaWriteBus = CacheMetaArrayWriteBus()
    val dataWriteBus = CacheDataArrayWriteBus()
    val tagWriteBus = CacheTagArrayWriteBus()

    val mem_getPutAcquire = Flipped(DecoupledIO(new TLBundleA(edge.bundle)))
    val mem_grantReleaseAck = DecoupledIO(new TLBundleD(edge.bundle))
    val mem_finish = DecoupledIO(new TLBundleE(edge.bundle))
    val mem_release = DecoupledIO(new TLBundleC(edge.bundle))    
  }

  val io = IO(new SSDCacheStage2IO(edge))

  //hit miss check
  val metaWay = io.metaReadResp
  val tagWay = io.tagReadResp
  val req = io.in.bits.req
  val addr = req.addr.asTypeOf(addrBundle)
  val hitVec = VecInit((tagWay zip metaWay).map(case(t, m) => m.asTypeOf(new ClientMetadata).isValid && (t === addr.tag))).asUInt
  val hitTag = hitVec.orR && io.in.valid      //hit tag and meta not nothing
    //has hit tag: find its coh
  val coh = Mux(hitTag, Mux1H(hitVec, metaWay).asTypeOf(new ClientMetadata), ClientMetadata.onReset)
  val hitMeta = coh.onAccess(req.cmd)._1
  val hit = hitTag && hitMeta && io.in.valid
    //miss need acquire and release(if not hitTag)
  val miss = !hit && io.in.valid
  m.asTypeOf(new ClientMetadata).isValid
    
    //find victim
  val victimWaymask = 3.U //Set 3 as default
  
    //find invalid
  val invalidVec = VecInit(metaWay.map(m => m.coh === ClientStates.Nothing)).asUInt
  val hasInvalidWay = invalidVec.orR
  val refillInvalidWaymask = Mux(invalidVec >= 8.U, "b1000".U,
    Mux(invalidVec >= 4.U, "b0100".U,
    Mux(invalidVec >= 2.U, "b0010".U, "b0001".U)))

  val waymask = Mux(hit || (miss && hitTag), hitVec, Mux(hasInvalidWay, refillInvalidWaymask, victimWaymask.asUInt))
  val wordMask = Mux(req.isWrite(), MaskExpand(req.wmask), 0.U(DataBits.W))
  
  //if hit: 看看是否需要更新元数据，更新元数据或者与DataArray交互数据
  val hitNewCoh = coh.onAccess(req.cmd)._3
  val needUpdateMeta = coh =/= hitNewCoh
  val hitWrite = hit && req.isWrite()
  val hitRead = hit && req.isRead()
  
    //update meta
  val metaHitWriteBus = Wire(CacheMetaArrayWriteBus()).apply(
    valid = hit && needUpdateMeta, setIdx = getMetaIdx(req.addr), waymask = waymask,
    coh = Wire(new MetaBundle).apply(coh = hitNewCoh)
  )

    //cmd write: write data to cache
  val dataRead = Mux1H(waymask, io.dataReadResp).data
  val dataMasked = MaskData(dataRead, req.wdata, wordMask)
  val dataHitWriteBus = Wire(CacheDataArrayWriteBus()).apply(
    data = Wire(new DataBundle).apply(dataMasked),
    valid = hitWrite, setIdx = Cat(addr.index, addr.wordIndex), waymask = waymask)
  
  //if miss

  //mmio | miss
    //core modules: acquireAccess
  val acquireAccess = Module(new AcquireAccess(edge))
  acquireAccess.io.mem_getPutAcquire <> io.mem_getPutAcquire
  acquireAccess.io.mem_grantAck <> mem_grantReleaseAck
  acquireAccess.io.mem_finish <> io.mem_finish
  acquireAccess.io.req := req
  acquireAccess.io.req.valid := miss || (io.in.bits.isMMIO && io.in.valid)
  acquireAccess.io.req.bits.wdata := Mux(hitTag, dataMasked, io.req.bits.wdata)
  acquireAccess.io.isMMIO := io.in.bits.isMMIO
  acquireAccess.io.waymask := waymask
  acquireAccess.io.hitTag := hitTag
  acquireAccess.io.cohOld := coh
  acquireAccess.io.resp.ready := io.out.ready

  val metaWriteBusAcquire = acquireAccess.io.metaWriteBus.req
  val dataWriteBusAcquire = acquireAccess.io.dataWriteBus.req

  val metaWriteArb = Module(new Arbiter(CacheMetaArrayWriteBus().req.bits, 2))
  val dataWriteArb = Module(new Arbiter(CacheDataArrayWriteBus().req.bits, 2))

  dataWriteArb.io.in(0) <> dataHitWriteBus
  dataWriteArb.io.in(1) <> acquireAccess.io.dataWriteBus
  io.dataWriteBus.req <> dataWriteArb.io.out

  metaWriteArb.io.in(0) <> metaHitWriteBus
  metaWriteArb.io.in(1) <> acquireAccess.io.metaWriteBus
  io.metaWriteBus.req <> metaWriteArb.io.out

  io.tagWriteBus.req <> acquireAccess.io.tagWriteBus

    //core modules: release
    //only miss but not hittag
  val release = Module(new Release(edge))

    //something for victim
  val needRel = miss && !hitTag && !hasInvalidWay
  val victimCoh = Mux1H(waymask, metaWay).asTypeOf(new ClientMetadata)
  val vicAddr = Cat(Mux1H(waymask, tagWay), addr.index, 0.U(6.W))
  
  release.io.req := req
  release.io.req.valid := needRel     //choose victim
  release.io.req.addr := vicAddr
  release.io.mem_release <> io.mem_release
  release.io.mem_releaseAck <> io.mem_grantReleaseAck
  release.io.victimCoh := victimCoh
  release.io.waymask := waymask
  io.dataReadBus <> release.io.dataReadBus

    //release操作完成
  val isrelDone = RegInit(false.B)
  when (release.io.release_ok) {isrelDone := true.B}
  when (io.out.fire()) {isrelDone := false.B}
  val relOK = !needRel || (needRel && isrelDone)
  
  io.out := acquireAccess.io.resp
  io.out.valid := io.in.valid && (hit || (miss && io.resp.valid && relOK)) 
  io.out.bits.rdata := Mux(hit, dataRead, acquireAccess.io.resp.bits.rdata)
  io.in.ready := io.out.ready

}

class SSDCache(implicit val cacheConfig: SSDCacheConfig) extends CacheModule extends LazyModule{
  
  val clientParameters = TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "dcache",
      sourceId = IdRange(srcBits + 1.U),
      supportsProbe = LineSize
    )),
    requestFields = Seq(),
    echoFields = Seq()
  )

  val clientNode = TLClientNode(Seq(clientParameters))

  lazy val module = new SSDCacheImp(this)
}

class SSDCacheImp(outer: SSDCache) extends CacheModule extends LazyModuleImp(outer) with HasSSDCacheIO { 

  val (bus, edge) = outer.clientNode.out.head
  // cache pipeline
  val s1 = Module(new SSDCacheStage1)
  val s2 = Module(new SSDCacheStage2(edge))

  //core modules
  val probe = Module(new Probe(edge))

  //meta 
  val tagArray = Module(new MetaSRAMTemplateWithArbiter(nRead = 2, new TagBundle, set = Sets, way = Ways, shouldReset = true))
  val metaArray = Module(new MetaSRAMTemplateWithArbiter(nRead = 2, new MetaBundle, set = Sets, way = Ways, shouldReset = true))
  val dataArray = Module(new SRAMTemplateWithArbiter(nRead = 3, new DataBundle, set = Sets * LineBeats, way = Ways))
//  val metaArray = Module(new MetaSRAMTemplateWithArbiter(nRead = 1, new MetaBundle, set = Sets, way = Ways, shouldReset = true))
//  val dataArray = Module(new DataSRAMTemplateWithArbiter(nRead = 2, new DataBundle, set = Sets * LineBeats, way = Ways))

  if (cacheName == "icache") {
    metaArray.reset := reset.asBool
  }

  s1.io.in <> io.in.req

  s2.io.mem_getPutAcquire <> bus.a 
  s2.io.mem_release <> bus.c
  s2.io.mem_grantReleaseAck <> bus.d 
  s2.io.mem_finish <> bus.e 
  
  probe.mem_probe <> bus.b
  probe.mem_probeAck <> bus.c
  
  val channelCArb = Module(new channelCArb(edge))
  channelCArb.io.in(0) <> probe.mem_probeAck
  channelCArb.io.in(1) <> s2.io.mem_release

  PipelineConnect(s1.io.out, s2.io.in, s2.io.out.fire(), io.flush)

  io.in.resp <> s2.io.out
  s2.io.flush := io.flush
  io.mmio <> s2.io.mmio

  metaArray.io.r(0) <> s1.io.metaReadBus
  metaArray.io.r(1) <> probe.io.metaReadBus
  dataArray.io.r(0) <> s1.io.dataReadBus
  dataArray.io.r(1) <> s2.io.dataReadBus
  dataArray.io.r(2) <> probe.io.dataReadBus
  tagArray.io.r(0) <> s1.io.tagReadBus
  tagArray.io.r(1) <> probe.io.tagReadBus

  val metaWriteArb = Module(new Arbiter(CacheMetaArrayWriteBus().req.bits, 2))
  val dataWriteArb = Module(new Arbiter(CacheDataArrayWriteBus().req.bits, 2))
  metaWriteArb.io.in(0) <> s2.io.metaWriteBus
  metaWriteArb.io.in(1) <> probe.io.metaWriteBus
  metaArray.io.w <> metaWriteArb.io.out
  dataWriteArb.io.in(0) <> probe.io.dataWriteBus
  dataWriteArb.io.in(1) <> s2.io.dataWriteBus
  dataArray.io.w <> dataWriteArb.io.out
  tagArray.io.w <> s2.io.tagWriteBus

  s2.io.metaReadResp := s1.io.metaReadBus.resp.data
  s2.io.tagReadResp := s1.io.tagReadBus.resp.data
  s2.io.dataReadResp := s1.io.dataReadBus.resp.data
}


/*object SSDCache {
  def apply(in: SimpleBusUC, mmio: SimpleBusUC, flush: Bool)(implicit cacheConfig: SSDCacheConfig) = {
    val cache = Module(new SSDCache)

    cache.io.flush := flush
    cache.io.in <> in
    mmio <> cache.io.mmio
    cache.io.out
  }
}*/
