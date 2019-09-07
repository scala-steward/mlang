package mlang.compiler

import Value.{Reference => _, _}
import mlang.utils.{Benchmark, Name, debug, warn}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import LongGen.Negative.{gen, dgen}

private case class Assumption(left: Long, right: Long, domain: Value, codomain: Closure)


case class UnificationFailedException(msg: String) extends Exception

object SolvableMetaForm {
  def unapply(a: Value): Option[(Value.Meta, Value.MetaState.Open, Seq[Value])] = {
    def rec(a: Value): Option[(Value.Meta, Value.MetaState.Open, Seq[Value])] = {
      a match {
        case App(l, as) => rec(l).map(pair => (pair._1, pair._2, pair._3 :+ as))
        case m@Meta(o: MetaState.Open) => Some((m, o, Seq.empty))
        case _ => None
      }
    }
    rec(a)
  }
}


trait Unifier extends Reifier with ElaboratorContextRebind with Evaluator with PlatformEvaluator {

  type Self  <: Unifier

  protected def unifyTerm(typ: Value, t1: Value, t2: Value): Boolean = {
    Benchmark.Unify {
      recTerm(typ, t1, t2)
    }
  }

  protected def subTypeOf(tm1: Value, tm2: Value): Boolean = {
    Benchmark.Unify {
      recType(tm1, tm2, mode = 1)
    }
  }

  // FIXME this is potentially non-terminating now, if the domain/codomain changes each time, this can happens for indexed types I think
  private val patternAssumptions = mutable.ArrayBuffer[Assumption]()

  private def sameTypePatternLambdaWithAssumptions(domain: Value, l1: PatternLambda, l2: PatternLambda): Boolean = {
    if (l1.id == l2.id) {
      true
    } else {
      if (patternAssumptions.exists(a => a.left == l1.id && a.right == l2.id && recType(a.domain, domain) && recTypeClosure(a.domain, a.codomain, l1.typ))) {
        true
      } else {
        patternAssumptions.append(Assumption(l1.id, l2.id, domain, l1.typ))
        recCases(domain, l1.typ, l1.cases, l2.cases)
      }
    }
  }

  private implicit def optToBool[T](opt: Option[T]): Boolean = opt.isDefined

  private def recClosureGraph(n1: ClosureGraph, n2: ClosureGraph, mode: Int = 0): Boolean = {
    n1.size == n2.size && {
      var g1 = n1
      var g2 = n2
      var eq = true
      var i = 0
      while (i < n1.size && eq) {
        val t1 = g1(i).independent.typ
        val t2 = g2(i).independent.typ
        eq = recType(t1, t2, mode)
        val g = Generic(gen(), t1)
        g1 = ClosureGraph.reduce(g1, i, g)
        g2 = ClosureGraph.reduce(g2, i, g)
        i += 1
      }
      eq
    }
  }

  private def recConstructor(c1: Constructor, c2: Constructor, mode: Int = 0): Boolean = {
    if (c1.eq(c2)) {
      true
    } else {
      c1.name == c2.name && c1.ims == c2.ims && recClosureGraph(c1.nodes, c2.nodes, mode)
    }
  }



  private def recTypeClosure(t: Value, c1: Closure, c2: Closure, mode: Int = 0): Option[Value] = {
    val c = Generic(gen(), t)
    val tt = c1(c)
    if (recType(tt, c2(c), mode)) {
      Some(tt)
    } else {
      None
    }
  }

  private def recAbsClosure(typ: Value, t1: AbsClosure, t2: AbsClosure): Boolean = {
    val c = Formula.Generic(dgen())
    recTerm(typ, t1(c), t2(c))
  }


  private def recTypeAbsClosure(t1: AbsClosure, t2: AbsClosure, mode: Int = 0): Boolean = {
    val c = Formula.Generic(dgen())
    recType(t1(c), t2(c), mode)
  }


  def recInd(dd1: Inductively, dd2: Inductively): Boolean = dd1.id == dd2.id && dd1.level == dd2.level

  @inline def maybeNominal(id1: Option[Inductively], id2: Option[Inductively], el: => Boolean): Boolean = {
    (id1, id2) match {
      case (None, None) =>
        // structural
        el
      case (Some(dd1), Some(dd2)) => recInd(dd1, dd2) // nominal
      case _ => false
    }

  }

  /**
    * mode = 1 left <subtype< right
    * mode = 0 left == right
    * mode =-1 right < left
    */
  private def recType(tm1: Value, tm2: Value, mode: Int = 0): Boolean = {
    if (tm1.eq(tm2)) {
      true
    } else {
      (tm1.whnf, tm2.whnf) match {
        case (Function(d1, i1, c1), Function(d2, i2, c2)) =>
          i1 == i2 && recType(d1, d2, -mode) && recTypeClosure(d1, c1, c2, mode)
        case (Universe(l1), Universe(l2)) =>
          mode match {
            case -1 => l2 <= l1
            case 0 => l1 == l2
            case 1 => l1 <= l2
          }
        case (Record(id1, m1, i1, n1), Record(id2, m2, i2, n2)) =>
          // need to check level because of up operator
          maybeNominal(id1, id2, m1 == m2 && i1 == i2 && recClosureGraph(n1, n2, mode))
        case (Sum(id1, c1), Sum(id2, c2)) =>
          maybeNominal(id1, id2, c1.size == c2.size && c1.zip(c2).forall(p => recConstructor(p._1, p._2, mode)))
        case (PathType(t1, l1, r1), PathType(t2, l2, r2)) =>
          recTypeAbsClosure(t1, t2, mode) &&
              recTerm(t1(Formula.False), l1, l2) &&
              recTerm(t1(Formula.True), r1, r2)
        case (t1, t2) =>
          recNeutral(t1, t2).map(_.whnf match {
            case Universe(_) => true
            case _ => false
          })
      }
    }
  }

  private def error(s: String) = throw UnificationFailedException(s)

  private def trySolve(m: Meta, vs: Seq[Value], t20: Value): Option[Value] = {
    Try(solve(m, vs, t20)) match {
      case Failure(exception) =>
        if (debug.enabled) {
          exception.printStackTrace()
        }
        exception match {
          case _: UnificationFailedException =>
            None
          case _: RebindNotFoundException =>
            None
          case e => throw e
        }
      case Success(v) =>
        Some(v)
    }
  }

  private def solve(m: Meta, vs: Seq[Value], t20: Value): Value = Benchmark.Solve {
    var MetaState.Open(_, typ) = m.state.asInstanceOf[MetaState.Open]
    val ref = rebindMeta(m)
    var ctx = drop(ref.up) // back to the layer where the meta is introduced
    val index = ref.index
    val os = vs.map {
      case o: Generic => o
      case _ => error("Spine is not generic")
    }
    val gs = os.map(_.id)
    for (i <- gs.indices) {
      val o = os(i)
      if (ctx.containsGeneric(o)) error("Spine is not linear")
      ctx = ctx.newParameterLayerProvided(Name.empty, o).asInstanceOf[Self]
    }
    val t2 = t20.fromOrThis // FIXME is this sound??
    if (t2.support().openMetas.contains(m)) {
      error("Meta solution contains self")
    }
    // this might throw error if scope checking fails
    var abs = ctx.reify(t2)
    for (g <- os) {
      abs = Abstract.Lambda(Abstract.Closure(Seq.empty, abs))
      typ = typ.whnf match {
        case f: Value.Function => f.codomain(g)
        case _ => logicError()
      }
    }
    // FIXME type checking??
    debug(s"meta solved with $abs", 1)
    val v = ctx.eval(abs)
    m.state = Value.MetaState.Closed(v)
    typ
  }

  private def recNeutral(tmm1: Value, tmm2: Value): Option[Value] = {
    (tmm1.whnf, tmm2.whnf) match {
      case (Generic(i1, v1), Generic(i2, v2)) =>
        if (i1 == i2) {
          if (v1.eq(v2)) {
            Some(v1)
          } else {
            logicError()
          }
        } else {
          None
        }
      case (App(l1, a1), App(l2, a2)) =>
        recNeutral(l1, l2).flatMap(_.whnf match {
          case Function(d, _, c) =>
          if (recTerm(d, a1, a2)) {
            Some(c(a1))
          } else {
            None
          }
          case _ => logicError()
        })
      case (Projection(m1, f1), Projection(m2, f2)) =>
        recNeutral(m1, m2).flatMap(_.whnf match {
          case r: Record if f1 == f2 => Some(r.projectedType(m1, f2))
          case _ => logicError()
        })
      case (PatternRedux(l1, s1), PatternRedux(l2, s2)) =>
        if (recType(l1.domain, l2.domain)) {
          val n = l1.domain
          if (recTerm(l1.domain, s1, s2)) {
            if (recTypeClosure(n, l1.typ, l2.typ) && sameTypePatternLambdaWithAssumptions(n, l1, l2)) {
              Some(l1.typ(s1))
            } else None
          } else None
        } else None
      case (PathApp(l1, d1), PathApp(l2, d2)) =>
        if (d1.normalForm == d2.normalForm) {
          recNeutral(l1, l2).map(_.whnf match {
            case PathType(typ, _, _) =>
              typ(d1)
            case _ => logicError()
          })
        } else {
          None
        }
      case (Hcom(t1, b1, r1), Hcom(t2, b2, r2)) =>
        if (!recType(t1, t2)) {
          logicError()
        } else if (recTerm(t1, b1, b2)) {
          if (r1.size == r2.size && r1.zip(r2).forall(p => {
            val n1 = p._1.restriction.normalForm
            val eqForm = n1 == p._2.restriction.normalForm
            if (eqForm) {
              n1.filter(Value.Formula.Assignments.satisfiable).forall(f => {
                newSyntaxDirectedRestrictionLayer(f).recAbsClosure(t1.restrict(f), p._1.body.restrict(f), p._2.body.restrict(f))
              })
            } else {
              false
            }
          })) {
            Some(t1)
          } else {
            None
          }
        } else {
          None
        }
      case (Transp(t1, d1, b1), Transp(t2, d2, b2)) =>
        if (d1.normalForm == d2.normalForm && recTypeAbsClosure(t1, t2) && recTerm(t1(Value.Formula.False), b1, b2)) {
          Some(t1(Value.Formula.True))
        } else {
          None
        }

      // FIXME solve meta headed?
//      case (SolvableMetaForm(m1, o1, gs1), SolvableMetaForm(m2, o2, gs2)) if o1.id == o2.id =>
//        if (gs1.size == gs2.size) {
//          gs1.zip(gs2).foldLeft(Some(o1.typ)) {
//          }
//        } else {
//          None
//        }
      case (SolvableMetaForm(m, _, gs), t2) =>
        trySolve(m, gs, t2)
      case (t1, SolvableMetaForm(m, _, gs)) =>
        trySolve(m, gs, t1)
      case _ => None
    }
  }

  private def recCases(domain: Value, codomain: Closure, c1: Seq[Case], c2: Seq[Case]): Boolean = {
    c1.size == c2.size && c1.zip(c2).forall(pair => {
      pair._1.pattern == pair._2.pattern && {
        Try { extractTypes(pair._1.pattern, domain) } match {
          case Success((ctx, itself)) =>
            recTerm(codomain(itself), pair._1.closure(ctx), pair._2.closure(ctx))
          case _ => false
        }
      }
    })
  }


  @inline def recTerms(ns: ClosureGraph, t1: Int => Value, t2: Int => Value): Boolean = {
    ns.indices.foldLeft(Some(ns) : Option[ClosureGraph]) { (as0, i) =>
      as0 match {
        case Some(as) =>
          val m1 = t1(i)
          if (recTerm(as(i).independent.typ, m1, t2(i))) {
            Some(ClosureGraph.reduce(as, i, m1))
          } else {
            None
          }
        case None =>
          None
      }
    }
  }

  /**
    * it is REQUIRED that t1 and t2 indeed has that type!!!!
    */
  private def recTerm(typ: Value, t1: Value, t2: Value): Boolean = {
    if (t1.eq(t2)) {
      true
    } else {
      (typ.whnf, t1.whnf, t2.whnf) match {
        case (Function(d, _, cd), s1, s2) =>
          val c = Generic(gen(), d)
          recTerm(cd(c), App(s1, c), App(s2, c))
        case (PathType(ty, _, _), s1, s2) =>
          val c = Formula.Generic(dgen())
          recTerm(ty(c), PathApp(s1, c), PathApp(s2, c))
        case (r: Record, m1, m2) =>
          recTerms(r.nodes, i => Projection(m1, i), i => Projection(m2, i))
        case (s: Sum, Construct(n1, v1), Construct(n2, v2)) =>
          n1 == n2 && { val c = s.constructors(n1) ;
            assert(c.nodes.size == v1.size && v2.size == v1.size)
            recTerms(c.nodes, v1, v2)
          }
        case (Universe(_), tt1, tt2) =>
          recType(tt1, tt2) // it will call unify neutral at then end
        case (_, tt1, tt2) => recNeutral(tt1, tt2)
      }
    }
  }

  private def extractTypes(
      pattern: Pattern,
      typ: Value
  ): (Seq[Generic], Value) = {
    val vs = mutable.ArrayBuffer[Generic]()

    def recs(maps: Seq[Pattern], graph0: ClosureGraph): Seq[Value]  = {
      var graph = graph0
      var vs =  Seq.empty[Value]
      for (i  <- maps.indices) {
        val it = graph(i).independent.typ
        val tv = rec(maps(i), it)
        graph = ClosureGraph.reduce(graph, i, tv)
        vs = vs :+ tv
      }
      vs
    }

    def rec(p: Pattern, t: Value): Value = {
      p match {
        case Pattern.Atom =>
          val ret = Generic(gen(), t)
          vs.append(ret)
          ret
        case Pattern.Make(maps) =>
          t.whnf match {
            case r: Record  =>
              if (maps.size == r.nodes.size) {
                Apps(Maker(t, -1), recs(maps, r.nodes))
              } else {
                logicError()
              }
            case _ => logicError()
          }
        case Pattern.Construct(name, maps) =>
          t.whnf match {
            case sum: Sum =>
              val c = sum.constructors(name)
              if (c.nodes.size == maps.size) {
                Apps(Maker(t, name), recs(maps, c.nodes))
              } else {
                logicError()
              }
            case _ => logicError()
          }
      }
    }
    val t = rec(pattern, typ)
    (vs.toSeq, t)
  }
}
