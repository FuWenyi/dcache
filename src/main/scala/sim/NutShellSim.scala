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

package sim

import system._
import nutcore.NutCoreConfig

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import bus.axi4._
//import device.AXI4RAM
import nutcore._
import utils.GTimer
import difftest._
import freechips.rocketchip.amba.AXI4RAM
import freechips.rocketchip.amba.AXI4Xbar
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.{AddressSet, LazyModule, LazyModuleImp}

class SimTop extends Module {
  val io = IO(new Bundle{
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
  })

  lazy val config = NutCoreConfig(FPGAPlatform = false)
  val soc = Module(new NutShell()(config))
  //val mem = LazyModule(new AXI4RAM(memByte = 128 * 1024 * 1024, useBlackBox = true))
  val mem = LazyModule(new AXI4RAM(address = AddressSet(0x80000000, 0x7ffffff)))     //128MB
  
  val xbar = AXI4Xbar()
  xbar := TLToAXI4() := soc.l2cache.node := soc.nutcore.dcache.clientNode
  xbar := soc.imem.node
  /*val l_simAXIMem = LazyModule(new AXI4RAMWrapper(
    soc.memAXI4SlaveNode, 128 * 1024 * 1024, useBlockBox = true
  ))
  val simAXIMem = Module(l_simAXIMem.module)*/
  
  // Be careful with the commit checking of emu.
  // A large delay will make emu incorrectly report getting stuck.
  //val memdelay = Module(new AXI4Delayer(0))
  val mmio = Module(new SimMMIO)

  soc.io.frontend <> mmio.io.dma

  //memdelay.io.in <> soc.io.mem 
  mem.node := TLDelayer(0) := xbar

  mmio.io.rw <> soc.io.mmio

  soc.io.meip := mmio.io.meip

  val log_begin, log_end, log_level = WireInit(0.U(64.W))
  log_begin := io.logCtrl.log_begin
  log_end := io.logCtrl.log_end
  log_level := io.logCtrl.log_level

  assert(log_begin <= log_end)
  //BoringUtils.addSource((GTimer() >= log_begin) && (GTimer() < log_end), "DISPLAY_ENABLE")

  // make BoringUtils not report boring exception when EnableDebug is set to false
  val dummyWire = WireInit(false.B)
  BoringUtils.addSink(dummyWire, "DISPLAY_ENABLE")

  io.uart <> mmio.io.uart
}
