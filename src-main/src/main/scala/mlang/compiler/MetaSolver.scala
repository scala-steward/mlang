package mlang.compiler

import mlang.compiler.semantic.given
import mlang.compiler.semantic.{Value, ValueConversion, MetaSpine, MetaState}
import mlang.compiler.dbi.given
import mlang.compiler.dbi.Abstract
import Value.{Reference => _, _}
import mlang.utils._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import GenLong.Negative.{gen, dgen}



case class UnificationFailedException(msg: String) extends Exception

trait MetaSolver extends ValueConversion with Reifier with ElaboratorContextRebind with Evaluator with CoreChecker {

  val Phase = Benchmark.Unify

  type Self  <: MetaSolver

  private def error(s: String) = throw UnificationFailedException(s)

  protected def trySolve(m: Meta, vs: MetaSpine, t20: Value): Option[Value] = {
    val res = Try(solve(m, vs, t20)) match {
      case Failure(exception) =>
        if (debug.enabled) {
          exception.printStackTrace()
        }
        exception match {
          case _: CoreCheckFailedException =>
            unifyFailed()
          case _: UnificationFailedException =>
            unifyFailed()
          case _: RebindNotFoundException =>
            warn("solve meta rebind not found!")
            unifyFailed()
          case e => throw e
        }
      case Success(v) =>
        Some(v)
    }
    res
  }


  private def solve(m: Meta, vs: MetaSpine, t20: Value): Value = Benchmark.Solve {
    var MetaState.Open(_, typ) = m.state.asInstanceOf[MetaState.Open]
    val ref = rebindMeta(m)
    var ctx = drop(ref.up) // back to the layer where the meta is introduced
    val index = ref.index
    for (i <- vs) {
      i match {
        case Left(value) =>
          if (ctx.containsGeneric(value)) error("Spine is not linear")
          ctx = ctx.newParameterLayerProvided(Name.empty, value).asInstanceOf[Self]
        case Right(value) =>
          if (ctx.containsGeneric(value)) error("Spine is not linear")
          ctx = ctx.newDimensionLayer(Name.empty, value).asInstanceOf[Self]
      }
    }
    var abs: Abstract = null
    try {
      val t2 = t20.bestReifyValue // RULES is this sound??
      if (!t2.support().openMetas.contains(m)) {
        abs = ctx.reify(t2)
      }
      // this might throw error if scope checking fails
    } catch {
      case e: RebindNotFoundException =>
    }
    // the "more compact version" contains generics, the whnf should contains less, try it again
    if (abs == null) {
      if (t20.support().openMetas.contains(m)) {
        error("metas contains itself")
      }
      abs = ctx.reify(t20)
    }
    abs = abs.diff(0, vs.size)
    for (g <- vs) {
      g match {
        case Left(v) =>
          abs = Abstract.Lambda(dbi.Closure(Seq.empty, abs))
          typ = typ.whnf match {
            case f: Value.Function => f.codomain(v)
            case _ => logicError()
          }
        case Right(v) =>
          abs = Abstract.PathLambda(dbi.Closure(Seq.empty, abs))
          typ = typ.whnf match {
            case f: Value.PathType => f.typ(v)
            case _ => logicError()
          }
      }
    }
    // FIXME(META) enable core checking
    ctx.check(abs, typ)
    debug(s"META solved with $abs", 1)
    val v = ctx.eval(abs)
    ctx.solveMeta(index, v, abs)
    m.state = MetaState.Closed(v)
    typ
  }

}
