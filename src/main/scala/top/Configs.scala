package top

import chisel3._
import chisel3.util._
import utils._
import nutcore._
import system._

import chipsalliance.rocketchip.config._
import SSDbackend.{DCacheParamsKey, DCacheParameters}

class BaseConfig(FPGAPlatform: Boolean = true) extends Config((site, here, up) => {
  case NutCoreParamsKey => NutCoreParameters(FPGAPlatform = FPGAPlatform)
  case DCacheParamsKey => DCacheParameters()
})

class DefaultConfig(FPGAPlatform: Boolean = true) extends Config(
  new BaseConfig(FPGAPlatform)
)