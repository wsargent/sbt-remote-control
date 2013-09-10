package com.typesafe.sbtrc.io

import java.io.File
import sbt.IO

object FileHasher {

  def sha512(file: File): String = {
    val md = java.security.MessageDigest.getInstance("SHA-512")
    val in = new java.io.FileInputStream(file);
    val buffer = new Array[Byte](8192)
    try {
      def read(): Unit = in.read(buffer) match {
        case x if x <= 0 => ()
        case size => md.update(buffer, 0, size); read()
      }
      read()
    } finally in.close()
    // Now migrate to a string we can compare
    digestToHexString(md.digest)
  }

  def digestToHexString(bytes: Array[Byte]): String = {
    val buf = new StringBuffer
    // TODO - error handling necessary?
    def byteToHex(b: Int) = HEX_CHARS(b)
    for (i <- 0 until bytes.length) {
      val b = bytes(i)
      buf append byteToHex((b >>> 4) & 0x0F)
      buf append byteToHex(b & 0x0F)
    }
    buf.toString
  }
  private val HEX_CHARS = "0123456789abcdef".toCharArray
}

/** abstract mechanism to write shim sbt files and update them. */
trait ShimWriter {
  def name: String
  protected def contents: String
  protected def relativeLocation: String = "project"
    
  protected def SHIM_FILE_NAME = "activator-" + name + "-shim.sbt"
  private def makeTarget(basedir: File): File =
    if(relativeLocation.isEmpty) new File(basedir, SHIM_FILE_NAME)
    else new File(new File(basedir, relativeLocation), SHIM_FILE_NAME)
  
  protected lazy val contentsFile = {
    val tmp = java.io.File.createTempFile(name, "sbt-shim")
    IO.write(tmp, contents)
    tmp.deleteOnExit()
    tmp
  }
  
  protected lazy val contentsSha = FileHasher.sha512(contentsFile)
  
  protected def isEmpty: Boolean = contents.isEmpty
  
  // update the shim file ONLY if it already exists. Returns true if it makes a change.
  def updateIfExists(basedir: File): Boolean = {
    val target = makeTarget(basedir)
    if (target.exists && FileHasher.sha512(target) != contentsSha) {
      // If we're moving to not requiring a shim, delete the dang file.
      if(isEmpty) IO.delete(target)
      else IO.copyFile(contentsFile, target)
      true
    } else {
      false
    }
  }

  // update the shim file EVEN IF it doesn't exist. Returns true if it makes a change.
  def ensureExists(basedir: File): Boolean = {
    val target = makeTarget(basedir)
    // If the file does not exist, but we aren't writing to it, don't bother 
    // creating an empty file.
    def doesNotExistAndEmpty = isEmpty && !target.exists
    // If the file exists *and* the contents match, this is true.
    def existsAndShaMatches = target.exists && FileHasher.sha512(target) == contentsSha
    
    // Now do the work.
    if(doesNotExistAndEmpty) false
    else if(existsAndShaMatches) {
      // We can safely delete the file if it's meant to be empty. 
      if(isEmpty) IO.delete(target)
      false
    } else {
      // Here we know there's some differential between what we have and what we want.
      // write the file.
      IO.copyFile(contentsFile, target)
      true
    }
  }
}
/** This will write build.sbt files and keep them up-to-date. */
case class GenericShimWriter(
  name: String,
  contents: String,
  override val relativeLocation: String
) extends ShimWriter

/** This writes shims for activator plugin shims. */
class ControlledPluginShimWriter(
    val name: String, 
    version: String, 
    sbtBinaryVersion: String = "0.12", 
    isEmpty: Boolean = false) 
    extends ShimWriter {

  private val cleanedVersion = sbtBinaryVersion.replaceAll("\\W+", "-")

  private val addBootResovlersString =
    if(sbtBinaryVersion == "0.12") ShimWriter.addBootResolversSetting
    else ""

  private val addSbtPluginString: String = """

// shim plugins are needed when plugins are not "UI aware"
// (we need an interface for the UI program rather than an interface
// for a person at a command line).
// In future plans, we want plugins to have a built-in ability to be
// remote-controlled by a UI and then we would drop the shims.
addSbtPlugin("com.typesafe.sbtrc" % "sbt-rc-""" + name + "-" + cleanedVersion + "\" % \"" + version + "\")\n"

  protected override val contents: String = addBootResovlersString + (if(isEmpty) "" else addSbtPluginString)
}

object ShimWriter {
  val alwaysIncludedShims = Set("eclipse", "idea", "defaults")

  lazy val eclipsePlugin12Shim =
    GenericShimWriter(
      name = "sbt-eclipse",
      contents = """addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.2.0")""",
      relativeLocation = "project")
  
  def sbt12Shims(version: String): Seq[ShimWriter] = Seq(
    new ControlledPluginShimWriter("defaults", version, "0.12"),
    new ControlledPluginShimWriter("eclipse", version, "0.12", isEmpty = true),
    new ControlledPluginShimWriter("idea", version, "0.12", isEmpty = true),
    new ControlledPluginShimWriter("play", version, "0.12"),
    eclipsePlugin12Shim,
    ideaPluginShim
  )
  // TODO - Configure this via property....
  
  lazy val eclipsePluginShim =
    GenericShimWriter(
      name = "sbt-eclipse",
      contents = """addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.3.0")""",
      relativeLocation = "project")
      
  // TODO - Configure this via property...
  lazy val ideaPluginShim =
    GenericShimWriter(
      name = "sbt-idea",
      contents = """addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.1")""",
      relativeLocation = "project")
      
  def sbt13Shims(version: String): Seq[ShimWriter] = Seq(
    new ControlledPluginShimWriter("defaults", version, "0.13", isEmpty = true),
    new ControlledPluginShimWriter("eclipse", version, "0.13", isEmpty = true),
    new ControlledPluginShimWriter("idea", version, "0.13", isEmpty = true),
    new ControlledPluginShimWriter("play", version, "0.13", isEmpty = true),
    eclipsePluginShim,
    ideaPluginShim
  )

  def knownShims(version: String, sbtVersion: String = "0.12"): Seq[ShimWriter] =
    sbtVersion match {
      case "0.12" => sbt12Shims(version)
      case "0.13" => sbt13Shims(version)
      case _ => sys.error("Unsupported sbt version: " + sbtVersion)
    }

  val addBootResolversSetting = """
// Note: This file is autogenerated by Builder.  Please do not modify!
// Full resolvers can be removed in sbt 0.13
fullResolvers <<= (fullResolvers, bootResolvers, appConfiguration) map {
  case (rs, Some(b), app) =>
    def getResolvers(app: xsbti.AppConfiguration): Option[Seq[xsbti.Repository]] =
      try Some(app.provider.scalaProvider.launcher.ivyRepositories.toSeq)
      catch { case _: NoSuchMethodError => None }
    def findLocalResolverNames(resolvers: Seq[xsbti.Repository]): Seq[String] =
      for {
        r <- resolvers
        if r.isInstanceOf[xsbti.IvyRepository]
        ivy = r.asInstanceOf[xsbti.IvyRepository]
        if ivy.url.getProtocol == "file"
      } yield ivy.id
    val newResolvers: Seq[Resolver] =
      getResolvers(app).map(findLocalResolverNames).getOrElse(Nil).flatMap(name => b.find(_.name == name))
    newResolvers ++ rs
  case (rs, _, _) => rs
}
"""
}
