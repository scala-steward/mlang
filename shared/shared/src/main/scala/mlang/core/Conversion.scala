package mlang.core

import Value._
import mlang.utils.debug

import scala.collection.mutable
import scala.util.{Success, Try}

private case class Assumption(left: Generic, right: Generic, domain: Value, codomain: Closure)



object Conversion {
  def equalTerm(typ: Value, t1: Value, t2: Value): Boolean = {
    new Conversion().equalTerm(typ, t1, t2)
  }

  def equalType(tm1: Value, tm2: Value): Boolean = {
    new Conversion().equalType(tm1, tm2)
  }
}

class Conversion {

  // TODO seems this can be globally shared
  private val patternAssumps = mutable.ArrayBuffer[Assumption]()

  private val typAssumeps = mutable.ArrayBuffer[(Value, Value)]()

  private def equalSameTypePatternLambdaWithAssumptions(domain: Value, l1: PatternLambda, l2: PatternLambda): Boolean = {
    if (l1.id == l2.id) {
      true
    } else {
      if (patternAssumps.exists(a => a.left == l1.id && a.right == l2.id && equalType(a.domain, domain) && equalTypeClosure(Int.MaxValue, a.domain, a.codomain, l1.typ))) {
        true
      } else {
        patternAssumps.append(Assumption(l1.id, l2.id, domain, l1.typ))
        equalCases(domain, l1.typ, l1.cases, l2.cases)
      }
    }
  }

  private val gen: GenericGen = new GenericGen.Negative()
  private val dgen: GenericGen = new GenericGen.Negative()

  private implicit def optToBool[T](opt: Option[T]): Boolean = opt.isDefined

  private def equalRecordFields(less: Int, n1: Seq[RecordNode], n2: Seq[RecordNode]): Boolean = {
    if (n1.map(_.name) == n2.map(_.name)) {
      n1.zip(n2).foldLeft(Some(Seq.empty[Value]) : Option[Seq[Value]]) { (as0, pair) =>
        as0 match {
          case Some(as) if pair._1.dependencies == pair._2.dependencies =>
            equalTypeMultiClosure(less, pair._1.dependencies.map(as), pair._1.closure, pair._2.closure).map(as :+ _)
          case None =>
            None
        }
      }
    } else {
      false
    }
  }

  private def equalConstructor(less: Int, c1: Constructor, c2: Constructor): Boolean = {
    if (c1.eq(c2)) {
      true
    } else {
      c1.name == c2.name && c1.parameters == c2.parameters && c1.nodes.size == c2.nodes.size && c1.nodes.zip(c2.nodes).foldLeft(Some(Seq.empty[Value]) : Option[Seq[Value]]) { (as0, pair) =>
          as0 match {
            case Some(as) =>
              equalTypeMultiClosure(less, as, pair._1, pair._2).map(as :+ _)
            case None =>
              None
          }
      }
    }
  }



  private def equalTypeMultiClosure(less: Int, ts: Seq[Value], c1: MultiClosure, c2: MultiClosure): Option[Value] = {
    val cs = ts.map(t => OpenReference(gen(), t))
    val t = c1(cs)
    if (equalType(less, t, c2(cs))) {
      Some(t)
    } else {
      None
    }
  }

  private def equalTypeClosure(less: Int, t: Value, c1: Closure, c2: Closure): Option[Value] = {
    val c = OpenReference(gen(), t)
    val tt = c1(c)
    if (equalType(less, tt, c2(c))) {
      Some(tt)
    } else {
      None
    }
  }

  private def equalType(tm1: Value, tm2: Value): Boolean = equalType(Int.MaxValue, tm1, tm2)

  private def equalType(less: Int, tm10: Value, tm20: Value): Boolean = {
    val tm1 = tm10.whnf
    val tm2 = tm20.whnf
    if (tm1.eq(tm2)) {
      true
    } else if (typAssumeps.exists(a => a._1.eq(tm1) && a._2.eq(tm2))) { // recursive defined sum and record
      // TODO handle parameterized recursively defined ones, we only allow them in top level
      true
    } else {
      typAssumeps.append((tm1, tm2))
      (tm1, tm2) match {
        case (Function(d1, c1), Function(d2, c2)) =>
          equalType(less, d1, d2) && equalTypeClosure(less, d1, c1, c2)
        case (Universe(l1), Universe(l2)) =>
          l1 == l2 && l1 <= less
        case (Record(l1, n1), Record(l2, n2)) =>
          l1 == l2 && l1 <= less && equalRecordFields(l1, n1, n2)
        case (Sum(l1, c1), Sum(l2, c2)) =>
          l1 == l2 && l1 <= less && c1.size == c2.size && c1.zip(c2).forall(p => equalConstructor(l1, p._1, p._2))
        case (PathType(t1, l1, r1), PathType(t2, l2, r2)) =>
          val a = t1(Dimension.False)
          val b = t2(Dimension.False)
          // we can call equal term here because we know tm1 and tm2 is well typed
          if (equalType(less, a, b) && equalTerm(a, l1, l2)) {
            val c = t1(Dimension.True)
            val d = t2(Dimension.True)
            equalType(less, c, d) && equalTerm(c, r1, r2)
          } else {
            false
          }
        case (t1, t2) =>
          equalNeutral(t1, t2).map(_.whnf) match {
            case Some(Universe(l)) => l <= less
            case _ => false
          }
      }
    }
  }



  private def equalNeutral(t1: Value, t2: Value): Option[Value] = {
    (t1.whnf, t2.whnf) match {
      case (OpenReference(i1, v1), OpenReference(i2, v2)) =>
        if (i1 == i2) {
          if (debug.enabled) {
            if (!equalType(v1, v2)) {
              logicError()
            }
          }
          Some(v1)
        } else {
          None
        }
      case (Application(l1, a1), Application(l2, a2)) =>
        equalNeutral(l1, l2).map(_.whnf).flatMap {
          case Function(d, c) =>
          if (equalTerm(d, a1, a2)) {
            Some(c(a1))
          } else {
            None
          }
          case _ => logicError()
        }
      case (Projection(m1, f1), Projection(m2, f2)) =>
        equalNeutral(m1, m2).map(_.whnf).flatMap {
          case r: Record if f1 == f2 => Some(r.projectedType(r.nodes.indices.map(n => Projection(m1, n)), f2))
          case _ => logicError()
        }
      case (PatternStuck(l1, s1), PatternStuck(l2, s2)) =>
        equalNeutral(s1, s2).flatMap(n => {
          // TODO give an example why this is needed
          if (equalTypeClosure(Int.MaxValue, n, l1.typ, l2.typ) && equalSameTypePatternLambdaWithAssumptions(n, l1, l2)) {
            Some(l1.typ(s1))
          } else {
            None
          }
        })
      case (PathApplication(l1, d1), PathApplication(l2, d2)) =>
        if (d1 == d2) {
          equalNeutral(l1, l2).map(_.whnf).map(_ match {
            case PathType(typ, _, _) =>
              typ(d1)
            case _ => logicError()
          })
        } else {
          None
        }
      case (Hcom(d1, t1, b1, r1), Hcom(d2, t2, b2, r2)) =>
        ???
      case _ => None
    }
  }

  private def equalCases(domain: Value, codomain: Closure, c1: Seq[Case], c2: Seq[Case]): Boolean = {
    c1.size == c2.size && c1.zip(c2).forall(pair => {
      pair._1.pattern == pair._2.pattern && {
        Try { extractTypes(pair._1.pattern, domain) } match {
          case Success((ctx, itself)) =>
            equalTerm(codomain(itself), pair._1.closure(ctx), pair._2.closure(ctx))
          case _ => false
        }
      }
    })
  }


  /**
    * it is REQUIRED that t1 and t2 indeed has that type!!!!
    */
  private def equalTerm(typ: Value, t1: Value, t2: Value): Boolean = {
    if (t1.eq(t2)) {
      true
    } else {
      (typ.whnf, t1.whnf, t2.whnf) match {
        case (Function(d, cd), s1, s2) =>
          val c = OpenReference(gen(), d)
          equalTerm(cd(c), s1.app(c), s2.app(c))
        case (PathType(ty, _, _), s1, s2) =>
          val c = Dimension.OpenReference(dgen())
          equalTerm(ty(c), s1.papp(c), s2.papp(c))
        case (Record(_, ns), m1, m2) =>
          ns.zipWithIndex.foldLeft(Some(Seq.empty) : Option[Seq[Value]]) { (as0, pair) =>
            as0 match {
              case Some(as) =>
                val nm = pair._1.closure(pair._1.dependencies.map(as))
                if (equalTerm(nm, m1.project(pair._2), m2.project(pair._2))) {
                  Some(as :+ nm)
                } else {
                  None
                }
              case None =>
                None
            }
          }
        case (Sum(_, cs), Construct(n1, v1), Construct(n2, v2)) =>
          n1 == n2 && cs.find(_.name == n1).exists(c => {
            assert(c.nodes.size == v1.size && v2.size == v1.size)
            c.nodes.zip(v1.zip(v2)).foldLeft(Some(Seq.empty): Option[Seq[Value]]) { (as0, pair) =>
              as0 match {
                case Some(as) =>
                  val nm = pair._1(as)
                  if (equalTerm(nm, pair._2._1, pair._2._2)) {
                    Some(as :+ nm)
                  } else {
                    None
                  }
                case None =>
                  None
              }
            }
          })
        case (ttt, tt1, tt2) =>
          ttt match {
            case Universe(l) => equalType(l, tt1, tt2) // it will call equal neutral at then end
            case _ => equalNeutral(tt1, tt2)
          }
      }
    }
  }

  private def extractTypes(
      pattern: Pattern,
      typ: Value
  ): (Seq[OpenReference], Value) = {
    val vs = mutable.ArrayBuffer[OpenReference]()
    def rec(p: Pattern, t: Value): Value = {
      p match {
        case Pattern.Atom =>
          val ret = OpenReference(gen(), t)
          vs.append(ret)
          ret
        case Pattern.Make(maps) =>
          t.whnf match {
            case r@Record(_, nodes) =>
              if (maps.size == nodes.size) {
                var vs =  Seq.empty[Value]
                for (m  <- maps) {
                  val it = r.projectedType(vs, vs.size)
                  val tv = rec(m, it)
                  vs = vs :+ tv
                }
                Make(vs)
              } else {
                logicError()
              }
            case _ => logicError()
          }
        case Pattern.Construct(name, maps) =>
          t.whnf match {
            case Sum(_, cs) =>
              cs.find(_.name == name) match {
                case Some(c) =>
                  if (c.nodes.size == maps.size) {
                    val vs = new mutable.ArrayBuffer[Value]()
                    for ((m, n) <- maps.zip(c.nodes)) {
                      val it = n(vs)
                      val tv = rec(m, it)
                      vs.append(tv)
                    }
                    Construct(name, vs)
                  } else {
                    logicError()
                  }
                case _ => logicError()
              }
            case _ => logicError()
          }
      }
    }
    val t = rec(pattern, typ)
    (vs, t)
  }
}
