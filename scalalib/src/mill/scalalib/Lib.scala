package mill
package scalalib

import java.io.{File, FileInputStream}
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider

import ammonite.ops._
import ammonite.util.Util
import coursier.{Cache, Dependency, Fetch, Repository, Resolution}
import mill.Agg
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.util.Ctx
import sbt.testing._

import scala.collection.mutable

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] = upickle.default.macroRW
}

// analysisFile is represented by Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: Path, classes: PathRef)

object Lib{
  def compileJava(sources: Array[java.io.File],
                  classpath: Array[java.io.File],
                  javaOpts: Seq[String],
                  upstreamCompileOutput: Seq[CompilationResult])
                 (implicit ctx: mill.util.Ctx) = {
    val javac = ToolProvider.getSystemJavaCompiler()
    if (javac == null) {
      throw new Exception(
        "Your Java installation is not a JDK, so it can't compile Java code;" +
        " Please install the JDK version of Java")
    }

    rm(ctx.dest / 'classes)
    mkdir(ctx.dest / 'classes)
    val cpArgs =
      if(classpath.isEmpty) Seq()
      else Seq("-cp", classpath.mkString(File.pathSeparator))

    val args = Seq("-d", ctx.dest / 'classes) ++ cpArgs ++ javaOpts ++ sources

    javac.run(
      ctx.log.inStream, ctx.log.outputStream, ctx.log.errorStream,
      args.map(_.toString):_*
    )
    if (ls(ctx.dest / 'classes).isEmpty) mill.eval.Result.Failure("Compilation Failed")
    else mill.eval.Result.Success(CompilationResult(ctx.dest / 'zinc, PathRef(ctx.dest / 'classes)))
  }

  private val ReleaseVersion = raw"""(\d+)\.(\d+)\.(\d+)""".r
  private val MinorSnapshotVersion = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r

  def scalaBinaryVersion(scalaVersion: String) = {
    scalaVersion match {
      case ReleaseVersion(major, minor, _) => s"$major.$minor"
      case MinorSnapshotVersion(major, minor, _) => s"$major.$minor"
      case _ => scalaVersion
    }
  }

  def grepJar(classPath: Agg[Path], s: String) = {
    classPath
      .find(_.toString.endsWith(s))
      .getOrElse(throw new Exception("Cannot find " + s))
      .toIO
  }


  def depToDependencyJava(dep: Dep, platformSuffix: String = ""): Dependency = {
    dep match {
      case Dep.Java(dep, cross, force) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
                (if (!cross) "" else platformSuffix)
          )
        )
    }
  }
  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep match {
      case d: Dep.Java => depToDependencyJava(dep)
      case Dep.Scala(dep, cross, force) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix) +
              "_" + scalaBinaryVersion(scalaVersion)
          )
        )
      case Dep.Point(dep, cross, force) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix) +
              "_" + scalaVersion
          )
        )
    }


  def resolveDependenciesMetadata(repositories: Seq[Repository],
                                  depToDependency: Dep => coursier.Dependency,
                                  deps: TraversableOnce[Dep],
                                  mapDependencies: Option[Dependency => Dependency] = None) = {
    val depSeq = deps.toSeq
    mill.modules.Jvm.resolveDependenciesMetadata(
      repositories,
      depSeq.map(depToDependency),
      depSeq.filter(_.force).map(depToDependency),
      mapDependencies
    )
  }
  /**
    * Resolve dependencies using Coursier.
    *
    * We do not bother breaking this out into the separate ScalaWorker classpath,
    * because Coursier is already bundled with mill/Ammonite to support the
    * `import $ivy` syntax.
    */
  def resolveDependencies(repositories: Seq[Repository],
                          depToDependency: Dep => coursier.Dependency,
                          deps: TraversableOnce[Dep],
                          sources: Boolean = false,
                          mapDependencies: Option[Dependency => Dependency] = None): Result[Agg[PathRef]] = {
    val depSeq = deps.toSeq
    mill.modules.Jvm.resolveDependencies(
      repositories,
      depSeq.map(depToDependency),
      depSeq.filter(_.force).map(depToDependency),
      sources,
      mapDependencies
    )
  }
  def scalaCompilerIvyDeps(scalaVersion: String) = Agg[Dep](
    ivy"org.scala-lang:scala-compiler:$scalaVersion".forceVersion(),
    ivy"org.scala-lang:scala-reflect:$scalaVersion".forceVersion()
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Agg[Dep](
    ivy"org.scala-lang:scala-library:$scalaVersion".forceVersion()
  )
  def compilerBridgeIvyDep(scalaVersion: String) =
    Dep.Point(
      coursier.Dependency(coursier.Module("com.lihaoyi", "mill-bridge"), "0.1", transitive = false),
      cross = false,
      force = false
    )

  def listClassFiles(base: Path): Iterator[String] = {
    if (base.isDir) ls.rec(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }

  def discoverTests(cl: ClassLoader, framework: Framework, classpath: Agg[Path]) = {

    val fingerprints = framework.fingerprints()

    val testClasses = classpath.flatMap { base =>
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      if (!exists(base)) Nil
      else listClassFiles(base).flatMap { path =>
        val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
        val publicConstructorCount =
          cls.getConstructors.count(c => c.getParameterCount == 0 && Modifier.isPublic(c.getModifiers))

        if (Modifier.isAbstract(cls.getModifiers) || cls.isInterface || publicConstructorCount > 1) {
          None
        } else {
          (cls.getName.endsWith("$"), publicConstructorCount == 0) match{
            case (true, true) => matchFingerprints(cl, cls, fingerprints, isModule = true)
            case (false, false) => matchFingerprints(cl, cls, fingerprints, isModule = false)
            case _ => None
          }
        }
      }
    }

    testClasses
  }
  def matchFingerprints(cl: ClassLoader, cls: Class[_], fingerprints: Array[Fingerprint], isModule: Boolean) = {
    fingerprints.find {
      case f: SubclassFingerprint =>
        f.isModule == isModule &&
        cl.loadClass(f.superclassName()).isAssignableFrom(cls)

      case f: AnnotatedFingerprint =>
        val annotationCls = cl.loadClass(f.annotationName()).asInstanceOf[Class[Annotation]]
        f.isModule == isModule &&
        (
          cls.isAnnotationPresent(annotationCls) ||
          cls.getDeclaredMethods.exists(_.isAnnotationPresent(annotationCls))
        )

    }.map { f => (cls, f) }
  }

}
