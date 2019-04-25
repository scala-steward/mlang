package mlang.core

import mlang.core.Abstract._
import mlang.core.Context.Layers
import mlang.name.Name
import mlang.utils.Benchmark

import scala.collection.mutable



private trait ReifierContext extends ContextBuilder {
  def base: ReifierContextBase

  override type Self <: ReifierContext

  def reifyReference(r: Value.Reference): Abstract.TermReference = {
    rebindReference(r) match {
      case Some(t) => t
      case None =>
        base.saveOutOfScopeValue(r)
        rebindReference(r).get
    }
  }

  def reify(v: Value): Abstract = {
    v match {
      case Value.Universe(level) =>
        Universe(level)
      case Value.Function(domain, codomain) =>
        val (ctx, tm) = newTermLayer(Name.empty, domain)
        Function(reify(domain), ctx.reify(codomain(tm)))
      case Value.Record(level, nodes) =>
        val (ctx, vs) = nodes.foldLeft((newTermsLayer().asInstanceOf[ReifierContext], Seq.empty[Value])) { (as, _) =>
          val (cc, v) = as._1.newAbstraction(Name.empty, null)
          (cc, as._2 :+ v)
        }
        assert(vs.size == nodes.size)
        Record(level, nodes.map(a =>
          RecordNode(a.name, a.dependencies, ctx.reify(a.closure(a.dependencies.map(vs))))))
      case Value.Sum(level, constructors) =>
        Sum(level, constructors.map(c => {
          val (ctx, vs) = c.nodes.foldLeft((newTermsLayer().asInstanceOf[ReifierContext], Seq.empty[Value])) { (as, _) =>
            val (cc, v) = as._1.newAbstraction(Name.empty, null)
            (cc, as._2 :+ v)
          }
          assert(vs.size == c.nodes.size)
          Constructor(c.name, c.nodes.map(a => ctx.reify(a(vs))))
        }))
      case Value.PathType(ty, left, right) =>
        val (ctx, d) = newDimensionLayer(Name.empty)
        PathType(ctx.reify(ty(d)), reify(left), reify(right))
      case Value.AbstractType(ty) =>
        val (ctx, d) = newDimensionLayer(Name.empty)
        AbstractType(ctx.reify(ty(d)))
      case Value.Make(_) =>
        // we believe at least values from typechecker don't have these stuff
        // we can extends it when time comes
        ???
      case Value.Construct(_, _) =>
        ???
      case Value.Lambda(closure) =>
        val (ctx, n) = newTermLayer(Name.empty, null)
        Lambda(ctx.reify(closure(n)))
      case Value.PatternLambda(id, ty, cases) =>
        val (ctx, n) = newTermLayer(Name.empty, null)
        PatternLambda(id, ctx.reify(ty(n)), cases.map(c => {
          val (ctx, ns) = (0 until c.pattern.atomCount).foldLeft((newTermsLayer().asInstanceOf[ReifierContext], Seq.empty[Value])) { (ctx, _) =>
            val (c, ns) = ctx._1.newAbstraction(Name.empty, null)
            (c, ctx._2 :+ ns)
          }
          Case(c.pattern, ctx.reify(c.closure(ns)))
        }))
      case Value.PathLambda(body) =>
        val (ctx, n) = newDimensionLayer(Name.empty)
        PathLambda(ctx.reify(body(n)))
      case Value.Generic(id, _) =>
        rebindGeneric(id)
      case c: Value.Reference =>
        reifyReference(c)
      case Value.App(lambda, argument) =>
        App(reify(lambda), reify(argument))
      case Value.Projection(make, field) =>
        Projection(reify(make), field)
      case Value.PatternStuck(lambda, stuck) =>
        App(reify(lambda), reify(stuck))
      case Value.Maker(s, i) =>
        Maker(reify(s), i)
      case Value.Let(items, order, body) =>
        val ctx = items.foldLeft(newTermsLayer().asInstanceOf[ReifierContext]) { (ctx, item) =>
          ctx.newDefinition(Name.empty, null, item)
        }
        val abs = items.map(p => ctx.reify(p))
        Let(abs, order, ctx.reify(body))
      case Value.PathApp(left, stuck) =>
        PathApp(reify(left), reify(stuck))
      case Value.Coe(dir, tp, base) =>
        val (ctx, n) = newDimensionLayer(Name.empty)
        Coe(reify(dir), ctx.reify(tp(n)), reify(base))
      case Value.Hcom(dir, tp, base, faces) =>
        val (ctx, n) = newTermsLayer().newDimensionLayer(Name.empty)
        Hcom(reify(dir), reify(tp), reify(base), faces.map(r => Face(reify(r.restriction), ctx.reify(r.body(n)))))
      case Value.Com(dir, tp, base, faces) =>
        Com(reify(dir), {
          val (ctx, n) = newDimensionLayer(Name.empty)
          ctx.reify(tp(n))
        }, reify(base), {
          val (ctx, n) = newTermsLayer().newDimensionLayer(Name.empty)
          faces.map(r => Face(reify(r.restriction), ctx.reify(r.body(n))))
        })
      case Value.Restricted(a, pair) =>
        pair.foldLeft(reify(a)) { (c, p) =>
          Restricted(c, reify(p))
        }
    }
  }

  def reify(a: Value.DimensionPair): Abstract.DimensionPair = {
    Abstract.DimensionPair(reify(a.from), reify(a.to))
  }

  def reify(a: Value.Dimension): Abstract.Dimension = {
    rebindDimension(a)
  }
}

private class ReifierContextCont(override val base: ReifierContextBase, override val layers: Context.Layers) extends ReifierContext {
  def gen: LongGen.Negative = base.gen

  override type Self = ReifierContextCont
  override protected implicit def create(a: Layers): ReifierContextCont = new ReifierContextCont(base, a)
}

// this is the context of the let expression where out-of-scope reference is collected
private class ReifierContextBase(layersBefore: Context.Layers) extends ReifierContext {
  private val terms = new mutable.ArrayBuffer[Binder]()
  private var data = Seq.empty[(Int, Option[Abstract])]
  override protected val layers: Layers =  Layer.Terms(terms) +: layersBefore

  private var self: Value = _


  def saveOutOfScopeValue(r: Value.Reference): Unit = {
    val index = terms.size
    terms.append(Binder(0, Name.empty, null, true, false, r.value))
    val abs = if (r.value.eq(self)) {
      None : Option[Abstract]
    } else {
      Some(reify(r.value))
    }
    val k = (index, abs)
    data = data :+ k
  }

  def reifyValue(v: Value): Abstract = {
    self = v
    val body = reify(v)
    val c = data.count(_._2.isEmpty)
    assert(c <= 1)
    val order = data.map(_._1)
    val abs = data.sortBy(_._1).map(_._2.getOrElse(body))
    if (c == 1) {
      Let(abs, order, TermReference(0, data.find(_._2.isEmpty).get._1, 0))
    } else {
      Let(abs, order, body)
    }
  }

  override def base: ReifierContextBase = this
  val gen = new LongGen.Negative()
  override type Self = ReifierContextCont
  override protected implicit def create(a: Layers): ReifierContextCont = new ReifierContextCont(this, a)

}

trait Reifier extends Context {
  def reify(v: Value): Abstract = Benchmark.Reify { new ReifierContextBase(layers).reifyValue(v) }
}
