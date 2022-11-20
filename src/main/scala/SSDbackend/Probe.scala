package SSDbackend

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chisel3.experimental.IO
import utils._
import bus.simplebus._

import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.ClientMetadata
import chipsalliance.rocketchip.config.Parameters

class ProbeReq(implicit val cacheConfig: DCacheConfig) extends DCacheBundle{
  val opcode = UInt()
  val param  = UInt(TLPermissions.bdWidth.W)
  val source = UInt()
  val addr   = UInt(PAddrBits.W)
  val needData = Bool()

  /*def dump() = {
    Debug("ProbeReq source: %d opcode: %d addr: %x param: %d\n",
      source, opcode, addr, param)
  }*/
}

class Probe(edge: TLEdgeOut)(implicit val cacheConfig: DCacheConfig) extends DCacheModule {
  val io = IO(new Bundle {
    val mem_probe = Flipped(DecoupledIO(new TLBundleB(edge.bundle)))
    val mem_probeAck = DecoupledIO(new TLBundleC(edge.bundle))
    val metaReadBus = CacheMetaArrayReadBus()
    val metaWriteBus = CacheMetaArrayWriteBus()
    val tagReadBus = CacheTagArrayReadBus()
    val dataReadBus = CacheDataArrayReadBus()
  })

  // translate to inner req
  val req = new ProbeReq
  req.source := io.mem_probe.bits.source
  req.opcode := io.mem_probe.bits.opcode
  req.addr := io.mem_probe.bits.address
  req.param := io.mem_probe.bits.param
  req.needData := io.mem_probe.bits.data(0)

  //reg for req
  val reqReg = RegEnable(req.asUInt, io.mem_probe.fire()).asTypeOf(new ProbeReq)
  val addr = reqReg.addr.asTypeOf(addrBundle)

  //condition machine: s_probePerm|s_probeAck|s_probeBlock|s_probeAckData|   
  val s_idle :: s_probeP :: s_probeA :: s_probeB :: s_probeAD :: Nil = Enum(5)

  val state = RegInit(s_idle)

  io.metaReadBus.apply(io.mem_probe.fire() && state === s_idle, setIdx = getMetaIdx(req.addr))
  io.tagReadBus.apply(io.mem_probe.fire() && state === s_idle, setIdx = getMetaIdx(req.addr))

  //refill_count代表c线上refill到第几个了，读应该比它早一拍，比如它在refill第n个时应该读第n+1个
  val (_, _, release_done, refill_count) = edge.count(io.mem_probeAck)
  val count = Mux(state === s_probeB, 0.U, refill_count + 1.U)
  io.dataReadBus.apply(valid = state === s_probeB || state === s_probeAD,
    setIdx = Cat(addr.index, count))

  val metaWay = io.metaReadBus.resp.data 
  val tagWay = io.tagReadBus.resp.data

  //hit and select coh
  val waymask = VecInit(tagWay.map(t => (t.tag === addr.tag))).asUInt
  val coh = Mux1H(waymask, metaWay).asTypeOf(new ClientMetadata)
  val (probe_has_dirty_data, probe_shrink_param, probe_new_coh) = coh.onProbe(req.param)
  val dataRead = Mux1H(waymask, io.dataReadBus.resp.data).data

  val metaWriteBus = Wire(CacheMetaArrayWriteBus()).apply(
    valid = state === s_probeB || state === s_probeP, setIdx = addr.index, waymask = waymask,
    data = Wire(new DMetaBundle).apply(coh = probe_new_coh)
  )
  io.metaWriteBus.req <> metaWriteBus.req

  io.mem_probe.ready := Mux(state === s_idle, true.B, false.B) 
  io.mem_probeAck.valid := Mux(state === s_probeA || state === s_probeAD, true.B, false.B)

  val probeResponse = edge.ProbeAck(
    fromSource = reqReg.source,
    toAddress = reqReg.addr,
    lgSize = log2Ceil(LineSize).U,
    reportPermissions = probe_shrink_param
  )

  val probeResponseData = edge.ProbeAck(
    fromSource = reqReg.source,
    toAddress = reqReg.addr,
    lgSize = log2Ceil(LineSize).U,
    reportPermissions = probe_shrink_param,
    data = dataRead
  )

  io.mem_probeAck.bits := Mux(state === s_probeA, probeResponse, probeResponseData)

  switch(state) {
    //request for meta and data(id needdata)
    is(s_idle) {
      when(io.mem_probe.valid) {
        state := Mux(req.needData, s_probeB, s_probeP)
      }
    }
    //get meta and data, calculate new coh, update coh
    is (s_probeP) {
      state := s_probeA
    }
    is (s_probeB) {
      state := s_probeAD
    }
    is (s_probeA) {
      when(io.mem_probeAck.fire()) {
        state := s_idle
      }
    }
    is (s_probeAD) {
      when(release_done) {
        state := s_idle
      }
    }
  }
}