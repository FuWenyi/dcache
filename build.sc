import mill._, scalalib._
import coursier.maven.MavenRepository

//millSourcePath默认是./src，可以用override重写
trait CommonModule extends ScalaModule {

  def chiselOpt: Option[ScalaModule] = None

  override def scalaVersion = "2.12.13"

  override def scalacOptions = Seq("-Xsource:2.11")
}

trait HasXsource211 extends ScalaModule {
  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-unchecked",
      "-Xsource:2.11"
    )
  }
}

trait HasChisel3 extends ScalaModule {
   override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
    )
  }
   override def ivyDeps = Agg(
    ivy"edu.berkeley.cs::chisel3:3.5.0-RC1"
 )
}

trait HasChiselTests extends CrossSbtModule  {
  object test extends Tests {
    override def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.4", ivy"edu.berkeley.cs::chisel-iotesters:1.2+")
    def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
}

/*object difftest extends SbtModule with CommonModule with HasChisel3 {
  override def millSourcePath = os.pwd / "difftest"
}

object chiselModule extends CrossSbtModule with HasChisel3 with HasChiselTests with HasXsource211 {
  def crossScalaVersion = "2.12.13"
  override def moduleDeps = super.moduleDeps ++ Seq(
    difftest
  )
}*/

object configRocket extends `rocket-chip`.`api-config-chipsalliance`.`build-rules`.mill.build.config with PublishModule {
    override def millSourcePath = rcPath / "api-config-chipsalliance" / "design" / "craft"

    override def scalaVersion = T {
      rocketchip.scalaVersion()
    }

    override def pomSettings = T {
      rocketchip.pomSettings()
    }

    override def publishVersion = T {
      rocketchip.publishVersion()
    }
  }

  object hardfloatRocket extends `rocket-chip`.hardfloat.build.hardfloat {
    override def millSourcePath = rcPath / "hardfloat"

    override def scalaVersion = T {
      rocketchip.scalaVersion()
    }

    def chisel3IvyDeps = if(chisel3Module.isEmpty) Agg(
      common.getVersion("chisel3")
    ) else Agg.empty[Dep]
  }

  def hardfloatModule = hardfloatRocket

  def configModule = configRocket

}

object huancun extends CommonModule with SbtModule {

  override def millSourcePath = os.pwd / "huancun"

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip
  )
}

object difftest extends CommonModule with SbtModule {
  override def millSourcePath = os.pwd / "difftest"
}

object fudian extends CommonModule with SbtModule

trait CommonNutShell extends CommonModule with SbtModule {
  def rocketModule: PublishModule 
  def difftestModule: PublishModule
  def huancunModule: PublishModule
  def fudianModule: PublishModule

  override def millSourcePath = os.pwd

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketModule,
    difftestModule,
    huancunModule,
    fudianModule
  )
}

object NutShell extends CommonNutShell extends CrossSbtModule with HasChisel3 with HasChiselTests with HasXsource211 {
  override def rocketModule = rocketchip
  override def difftestModule = difftest
  override def huancunModule = huancun
  override def fudianModule = fudian
}
