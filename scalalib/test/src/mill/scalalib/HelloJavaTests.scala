package mill
package scalalib


import ammonite.ops.{%, %%, cp, ls, mkdir, pwd, rm, up}
import ammonite.ops.ImplicitWd._
import mill.define.Task
import mill.eval.Result
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath


object HelloJavaTests extends TestSuite {

  object HelloJava extends TestUtil.BaseModule{
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    trait JUnitTests extends TestModule{
      def testFrameworks = Seq("com.novocode.junit.JUnitFramework")
      def ivyDeps = Agg(ivy"com.novocode:junit-interface:0.11")
    }

    object core extends JavaModule{
      object test extends Tests with JUnitTests
    }
    object app extends JavaModule{
      def moduleDeps = Seq(core)
      object test extends Tests with JUnitTests
    }
  }
  val resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-java"

  def init()(implicit tp: TestPath) = {
    val eval = new TestEvaluator(HelloJava)
    rm(HelloJava.millSourcePath)
    rm(eval.outPath)
    mkdir(HelloJava.millSourcePath / up)
    cp(resourcePath, HelloJava.millSourcePath)
    eval
  }
  def tests: Tests = Tests {
    'compile - {
      val eval = init()

      val Right((res1, n1)) = eval.apply(HelloJava.core.compile)
      val Right((res2, 0)) = eval.apply(HelloJava.core.compile)
      val Right((res3, n2)) = eval.apply(HelloJava.app.compile)

      assert(
        res1 == res2,
        n1 != 0,
        n2 != 0,
        ls.rec(res1.classes.path).exists(_.last == "Core.class"),
        !ls.rec(res1.classes.path).exists(_.last == "Main.class"),
        ls.rec(res3.classes.path).exists(_.last == "Main.class"),
        !ls.rec(res3.classes.path).exists(_.last == "Core.class")
      )
    }
    'docJar  - {
      val eval = init()

      val Right((ref1, _)) = eval.apply(HelloJava.core.docJar)
      val Right((ref2, _)) = eval.apply(HelloJava.app.docJar)

      assert(
        %%("jar", "tf", ref1.get.path).out.lines.contains("hello/Core.html"),
        %%("jar", "tf", ref2.get.path).out.lines.contains("hello/Main.html")
      )
    }
    'noDocJar  - {
      val eval = init()
      object NoDoc extends TestUtil.BaseModule with JavaModule {
        def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
        override def docJar: Task[Option[PathRef]] = T(None)
      }

      val Right((None, _)) = eval.apply(NoDoc.docJar)
    }
    'test - {
      val eval = init()

      val Left(Result.Failure(ref1, Some(v1))) = eval.apply(HelloJava.core.test.test())

      assert(
        v1._2(0).fullyQualifiedName == "hello.MyCoreTests.lengthTest",
        v1._2(0).status == "Success",
        v1._2(1).fullyQualifiedName == "hello.MyCoreTests.msgTest",
        v1._2(1).status == "Failure"
      )

      val Right((v2, _)) = eval.apply(HelloJava.app.test.test())

      assert(
        v2._2(0).fullyQualifiedName == "hello.MyAppTests.appTest",
        v2._2(0).status == "Success",
        v2._2(1).fullyQualifiedName == "hello.MyAppTests.coreTest",
        v2._2(1).status == "Success"
      )
    }
    'failures - {
      val eval = init()

      val mainJava = HelloJava.millSourcePath / 'app / 'src / 'hello / "Main.java"
      val coreJava = HelloJava.millSourcePath / 'core / 'src / 'hello / "Core.java"

      val Right(_) = eval.apply(HelloJava.core.compile)
      val Right(_) = eval.apply(HelloJava.app.compile)

      ammonite.ops.write.over(mainJava, ammonite.ops.read(mainJava) + "}")

      val Right(_) = eval.apply(HelloJava.core.compile)
      val Left(_) = eval.apply(HelloJava.app.compile)

      ammonite.ops.write.over(coreJava, ammonite.ops.read(coreJava) + "}")

      val Left(_) = eval.apply(HelloJava.core.compile)
      val Left(_) = eval.apply(HelloJava.app.compile)

      ammonite.ops.write.over(mainJava, ammonite.ops.read(mainJava).dropRight(1))
      ammonite.ops.write.over(coreJava, ammonite.ops.read(coreJava).dropRight(1))

      val Right(_) = eval.apply(HelloJava.core.compile)
      val Right(_) = eval.apply(HelloJava.app.compile)
    }
  }
}
