package mlang.compiler

import mlang.compiler.Concrete._
import Declaration.Modifier
import mlang.compiler.dbi.given
import mlang.compiler.dbi.{Abstract, Dependency, DependencyType}
import mlang.compiler.Layer.Layers
import mlang.compiler.semantic.{Value, BuiltIn}
import mlang.compiler.semantic.given
import Value.{PathLambda, PatternRedux, StableCanonical}
import semantic.ClosureGraph
import mlang.utils._
import semantic.phi

import scala.annotation.Annotation
import scala.collection.mutable
import scala.language.implicitConversions
import scala.util.Random

class syntax_creation extends Annotation

private class IndState(val id: Long, typ: Abstract, var stop: Boolean, var top: Value, var apps: Seq[Value] = Seq.empty) {
  // returns a value is self
  def consume(level: Int): Option[(Value, dbi.Inductively)] = {
    if (stop) {
      None
    } else {
      val ret = dbi.Inductively(id, typ, (0 until apps.size).map(i => Abstract.Reference(i, -1, 0)).reverse)
      stop = true
      Some((Value.apps(top, apps), ret))
    }
  }


  def app(v: Value): IndState = {
    if (stop) this else {
      apps = apps :+ v
      this
    }
  }

}

private object IndState {
  val stop = new IndState(0, null, true, null)
}

object Elaborator {
  private val pgen = new GenLong.Positive()
  private val igen = new GenLong.Positive()
  def topLevel(): Elaborator = new Elaborator(Seq.empty).newDefinesLayer()
}

// TODO because these are mutational code (see Readme about meta), reread these so no errors
// TODO there are some marked as "syntax_creation" which modify concrete syntax directly, and this is unwanted
class Elaborator private(protected override val layers: Layers)
    extends ElaboratorContextBuilder
        with ElaboratorContextLookup
        with ElaboratorContextRebind
        with ElaboratorContextForEvaluator
        with Evaluator with PlatformEvaluator with MetaSolver {


  override type Self = Elaborator
  override protected implicit def create(a: Layers): Self = new Elaborator(a)

  private def doForValidFormulaOrThrow[T](f: semantic.Formula, a: semantic.Assignments => T): T = {
    val davn = f.normalForm
    val valid = davn.filter(_.satisfiable)
    if (valid.isEmpty) {
      throw ElaboratorException.RemoveStaticFalseOrUnsatisfiableFace()
    } else {
      if (valid.size > 1) throw ElaboratorException.StaticDisjunctionCurrentlyNotSupported()
      a(valid.head)
    }
  }

  private def checkCompatibleCapAndFaces(
                                  faces: Seq[Concrete.Face],
                                  bt: semantic.AbsClosure,
                                  bv: Value
  ): dbi.System = {
    import semantic.Formula
    val nfs = mutable.ArrayBuffer.empty[semantic.Assignments]
    val tms = mutable.ArrayBuffer.empty[Value]
    faces.indices.map { i =>
      val a = faces(i)
      val (dav, daa) = checkAndEvalFormula(a.dimension)
      doForValidFormulaOrThrow(dav, asgn0 => {
        nfs.append(asgn0)
        val ctx0 = newSyntaxDirectedRestrictionLayer(asgn0)
        val btr = bt.restrict(asgn0)
        // FIXME(META) no hurry to finalize this context? use information in cap to infer?
        val (dim, na) = ctx0.checkLine(a.term, btr)
        val naa = ctx0.evalAbsClosure(na)
        val nv = naa(Formula.False)
        tms.append(nv)
        if (!ctx0.unifyTerm(btr(dim), bv.restrict(asgn0), nv)) { // nv.restrict(asgn0)
          throw ElaboratorException.CapNotMatching()
        }
        for (j <- 0 until i) {
          val asgn1 = nfs(j)
          // this might evaluate the dimensions to new values
          val dfv = asgn1 ++ asgn0
          // only used to test if this restriction is false face or not
          if (dfv.satisfiable) {
            val ctx0 = newSyntaxDirectedRestrictionLayer(dfv)
            val (ctx1, dim) = ctx0.newDimensionLayer(Name.empty)
            if (!ctx1.unifyTerm(
              btr(dim).restrict(dfv),
              nv.restrict(dfv),
              tms(j).restrict(dfv))) {
              throw ElaboratorException.FacesNotMatching()
            }
          }
        }
        (daa, na)
      })
    }.toMap
  }


  private def checkLine(a: Concrete, typ: semantic.AbsClosure): (semantic.Formula.Generic, dbi.Closure) = {
    a match {
      case Concrete.Lambda(n, b, _, body) =>
        if (b) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
        val (ctx, dim) = newDimensionLayer(n)
        val ta = ctx.check(body, typ(dim))
        (dim, dbi.Closure(ctx.finish(), ta))
      case _ =>
        val (ctx, dim) = newDimensionLayer(Name.empty) // it is ok to infer in this context, as the name is empty so it doesn't affect much
        val (tv, ta) = ctx.infer(a)
        tv.whnf match {
          case j@Value.PathType(_, _, _) =>
            (dim, dbi.Closure(ctx.finish(), Abstract.PathApp(ta, dbi.Formula.Reference(0, -1))))
          case _ => throw ElaboratorException.ExpectingLambdaTerm()
        }
    }
  }

  private def checkTypeLine(a: Concrete): (Int, dbi.Closure) = {
    a match {
      case Concrete.Lambda(n, b, _, body) =>
        if (b) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
        val ctx = newDimensionLayer(n)._1
        val (l, ta0) = ctx.inferLevel(body)
        val ta = dbi.Closure(ctx.finish(), ta0)
        (l, ta)
      case _ =>
        val ctx = newDimensionLayer(Name.empty)._1
        val (tv, ta) = ctx.infer(a)
        tv.whnf match {
          case j@Value.PathType(u, _, _) =>
            val clo = dbi.Closure(ctx.finish(), Abstract.PathApp(ta, dbi.Formula.Reference(0, -1)))
            val lvl = u(semantic.Formula.Generic(ElaboratorContextBuilder.dgen())).whnf.asInstanceOf[Value.Universe].level
            (lvl, clo)
          case _ => throw ElaboratorException.ExpectingLambdaTerm()
        }
    }
  }

  @scala.annotation.tailrec
  private def finishOffImplicits(v: Value, abs: Abstract): (Value, Abstract) = {
    v.whnf match {
      case Value.Function(d, i, c) if i =>
        val (mv, ma) = newMeta(d)
        finishOffImplicits(c(mv), Abstract.App(abs, ma))
      case _ => (v, abs)
    }
  }


  def checkRecord(ind: Option[dbi.Inductively], r: Concrete.Record): (Value, Abstract) = {
    val Concrete.Record(fields) = r
    for (f <- fields) {
      if (f.names.isEmpty) throw ElaboratorException.MustBeNamed()
    }
    for (i <- r.names.indices) {
      for (j <- (i + 1) until r.names.size) {
        if (r.names(i)._2 intersect r.names(j)._2) {
          throw ElaboratorException.FieldsDuplicated()
        }
      }
    }
    val ctx = newParametersLayer()
    val (fl, fs, _) = ctx.inferFlatLevel(NameType.flatten(fields))
    (Value.Universe(fl), Abstract.Record(
      ind,
      fs.map(_._1),
      dbi.ClosureGraph(fs.map(a => dbi.ClosureGraph.Node(a._2, a._3.term.dependencies(0).filter(a => a.x == 0 && a.typ == DependencyType.Value).map(_.i).toSeq.sorted, a._3)))))
  }

  def checkSum(tps: Option[(Value, Option[(Value, dbi.Inductively)], Int)], sum: Concrete.Sum): (Value, Abstract) = {
    val Concrete.Sum(constructors) = sum
    for (i <- constructors.indices) {
      for (j <- (i + 1) until constructors.size) {
        if (constructors(i).name.intersect(constructors(j).name)) {
          throw ElaboratorException.TagsDuplicated()
        }
      }
    }
    val selfValue = tps.map(pair => {
      pair._2.map(_._1).getOrElse(Value.Generic(ElaboratorContextBuilder.gen(), pair._1))
    })
    var ctx = newParametersLayer(selfValue)
    var isHit = false
    val fs = constructors.map(c => {
      val seq = NameType.flatten(c.term)
      val tpd = seq.takeWhile(_._3 != Concrete.I)
      val is = seq.drop(tpd.size)
      for (i <- is) if (i._1) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
      val iss = is.map(_._2)
      val (ll, tt, ctx0) = ctx.inferFlatLevel(tpd)
      ctx = ctx0
      val valueGraph = tt.zipWithIndex.map(kk => dbi.ClosureGraph.Node(kk._1._2, 0 until kk._2, kk._1._3))
      // FIXME check restrictions is compatible
      def checkRestrictions(sv: Value): Seq[(dbi.Formula, dbi.Closure)] = {
        // NOW: check extensions
        val (dimCtx, dims) = iss.foldLeft((ctx0, Seq.empty[Long])) { (ctx, n) =>
          val (c, l) = ctx._1.newDimension(n)
          isHit = true
          (c, ctx._2 :+ l.id)
        }
        ctx = dimCtx
        c.restrictions.map(r => {
          val (dv, da) = ctx.checkAndEvalFormula(r.dimension)
          val rctx = ctx.newReifierRestrictionLayer(dv) // remind me: why this is a reifier layer?
          val bd = rctx.check(r.term, sv)
          val res = dbi.Closure(rctx.finish(), bd)
          (da, res)
        })
      }
      val es: dbi.System = if (is.nonEmpty) {
        selfValue match {
          case Some(sv) =>
            checkRestrictions(sv).toMap
          case None =>
            throw ElaboratorException.CannotInferTypeOfHit()
        }
      } else {
        if (c.restrictions.nonEmpty) throw ElaboratorException.HitContainingExternalDimension()
        else Map.empty
      }
      val closureGraph = dbi.ClosureGraph(valueGraph, iss.size, es)
      selfValue match {
        case Some(_) =>
          ctx = ctx.newConstructor(c.name, eval(closureGraph))
        case None =>
          ctx = newParametersLayer(None) // here we don't use ctx anymore, because we are not remembering the previous constructors
      }
      (c.name, ll, closureGraph)
    })
    val fl = tps.map(_._3).getOrElse(
      if (fs.isEmpty) throw ElaboratorException.EmptySumMustHaveTypeAnnotation() else fs.map(_._2).max)
    (Value.Universe(fl), Abstract.Sum(tps.flatMap(_._2.map(_._2)), isHit, fs.map(a =>
      dbi.Constructor(a._1, a._3))))
  }

  def checkConstructApp(sumValue: Value, index: Int, nodes: semantic.ClosureGraph, arguments: Seq[(Boolean, Concrete)]): Abstract = {
    val ns = arguments.take(nodes.size)
    val res1 = inferGraphValuesPart(nodes, ns)
    val ds = arguments.drop(nodes.size)
    if (ds.size != nodes.dimSize) throw ElaboratorException.NotFullyAppliedConstructorNotSupportedYet()
    if (ds.exists(_._1)) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
    val res2 = ds.map(a => checkAndEvalFormula(a._2))
    Abstract.Construct(index, res1.map(_._1), res2.map(_._2), if (nodes.dimSize == 0) Map.empty else reifyEnclosedSystem(nodes.reduceAll(res1.map(_._2)).reduce(res2.map(_._1)).restrictions()))
  }

  def checkSumConstructApp(sum: Value.Sum, index: Int, arguments: Seq[(Boolean, Concrete)]) =
    checkConstructApp(sum, index, sum.constructors(index).nodes, arguments)

  private def infer(
                     term: Concrete,
                     noReduceMore: Boolean = false): (Value, Abstract) = {
    debug(s"infer $term")
    def reduceMore(v: Value, abs: Abstract): (Value, Abstract) = {
      if (noReduceMore) {
        (v, abs)
      } else {
        finishOffImplicits(v, abs)
      }
    }


    def inferProjectionApp(left: Concrete, right: Concrete, arguments: Seq[(Boolean, Concrete)]): (Value, Abstract) = {
      val (lt, la) = infer(left)
      val lv = eval(la)
      lazy val ltr = lt.whnf.asInstanceOf[Value.Record]
      def error() = throw ElaboratorException.UnknownProjection(right.toString)
      var index = -1
      def calIndex(a: Text => Int) = {
        index = right match {
          case Concrete.Reference(name) => a(name)
          case _ => -1
        }
        index >= 0
      }
      lt.whnf match {
        case m: Value.Record if calIndex(t => ltr.names.indexWhere(_.by(t))) =>
          val (a,b) = inferApp(m.projectedType(lv, index), Abstract.Projection(la, index), arguments)
          reduceMore(a, b)
        case _ =>
          lv.whnf match {
            // TODO user defined projections for a type, i.e.
            // TODO [issue 7] implement const_projections syntax
            case r: Value.Record if right == Concrete.Make =>
              (lv, Abstract.Make(inferGraphValuesPart(r.nodes, arguments).map(_._1)))
            case r: Value.Sum if calIndex(t => r.constructors.indexWhere(_.name.by(t))) =>
              (r, checkSumConstructApp(r, index, arguments))
            case _ =>
              error()
          }
      }
    }
    val res = term match {
      case Concrete.Type =>
        (Value.Universe.level1, Abstract.Universe(0))
      case Concrete.Up(a, b) =>
        a match {
          case Concrete.Up(c, d) =>
            // @syntax_creation
            infer(Concrete.Up(c, b + d))
          case Concrete.Type =>
            (Value.Universe.suc(b), Abstract.Universe(if (Value.Universe.TYPE_IN_TYPE) 0 else b))
          case Concrete.Reference(ref) =>
            lookupTerm(ref, b) match {
              case NameLookupResult.Typed(typ, abs) =>
                abs match {
                  case r: Abstract.Reference if r.up == layers.size - 1 =>
                    reduceMore(typ, r)
                  //reduceMore(binder.up(b), Abstract.Up(abs, b))
                  case _ => throw ElaboratorException.UpCanOnlyBeUsedOnTopLevelDefinitionOrUniverse()
                }
              case _: NameLookupResult.Construct =>
                throw ElaboratorException.UpCanOnlyBeUsedOnTopLevelDefinitionOrUniverse()
            }
          case _ => throw ElaboratorException.UpCanOnlyBeUsedOnTopLevelDefinitionOrUniverse()
        }
      case Concrete.Reference(name) =>
        // should lookup always return a value? like a open reference?
        lookupTerm(name, 0) match {
          case NameLookupResult.Typed(binder, abs) =>
            reduceMore(binder, abs)
          case NameLookupResult.Construct(self, index, closure) =>
            if (closure.size != 0 || closure.dimSize != 0) {
              throw ElaboratorException.NotFullyAppliedConstructorNotSupportedYet()
            } else {
              (self, Abstract.Construct(index, Seq.empty, Seq.empty, Map.empty))
            }
        }
      case Concrete.Hole =>
        throw ElaboratorException.CannotInferMeta()
      case Concrete.True =>
        throw ElaboratorException.TermSortWrong()
      case Concrete.False =>
        throw ElaboratorException.TermSortWrong()
      case _: Concrete.And =>
        throw ElaboratorException.TermSortWrong()
      case _: Concrete.Or =>
        throw ElaboratorException.TermSortWrong()
      case _: Concrete.Neg =>
        throw ElaboratorException.TermSortWrong()
      case Concrete.I =>
        throw ElaboratorException.TermICanOnlyAppearInDomainOfFunction()
      case Concrete.Make =>
        throw ElaboratorException.CannotInferMakeExpression()
      case _: Concrete.Glue =>
        throw ElaboratorException.CannotInferVMakeExpression()
      case Concrete.Cast(v, t) =>
        val (_, ta) = inferLevel(t)
        val tv = eval(ta)
        (tv, check(v, tv))
      case Concrete.Function(domain, codomain) =>
        if (domain.isEmpty) throw ElaboratorException.EmptyTelescope()
        val (l, v) = inferTelescope(NameType.flatten(domain), codomain)
        (Value.Universe(l), v)
      case r: Concrete.Record =>
        checkRecord(None, r)
      case s: Concrete.Sum =>
        checkSum(None, s)
      case Concrete.Transp(tp, direction, base) =>
        val (dv, da) = checkAndEvalFormula(direction)
        val (tv, ta) = checkTypeLine(tp)
        val cl = evalAbsClosure(ta)
        val (ctx, dim) = newDimensionLayer(Name.empty)
        val constant = dv.normalForm.filter(_.satisfiable).forall(asg => {
          ctx.newSyntaxDirectedRestrictionLayer(asg).unifyTerm(Value.Universe(tv), cl(dim).restrict(asg), cl(semantic.Formula.False).restrict(asg))
        })
        if (!constant) {
          throw ElaboratorException.TranspShouldBeConstantOn()
        }
        val ba = check(base, cl(semantic.Formula.False))
        (cl(semantic.Formula.True), Abstract.Transp(ta, da, ba))
      case Concrete.Comp(tp, base, faces) =>
        val (_, ta) = checkTypeLine(tp)
        val cl = evalAbsClosure(ta)
        val ba = check(base, cl(semantic.Formula.False))
        val rs = checkCompatibleCapAndFaces(faces, cl, eval(ba))
        (cl(semantic.Formula.True), Abstract.Comp(ta, ba, rs))
      case Concrete.Hcomp(base, faces) =>
        val (bt, ba) = infer(base)
        val bv = eval(ba)
        val rs = checkCompatibleCapAndFaces(faces, semantic.AbsClosure(bt), bv)
        val btr = reify(bt.bestReifyValue)
        debug(s"infer hcom type $btr", 1)
        (bt, Abstract.Hcomp(btr, ba, rs))
      case Concrete.PathType(typ, left, right) =>
        typ match {
          case Some(tp) =>
            val (tl, ca) = checkTypeLine(tp)
            val tv = evalAbsClosure(ca)
            val la = check(left, tv(semantic.Formula.False))
            val ra = check(right, tv(semantic.Formula.True))
            (Value.Universe(tl), Abstract.PathType(ca, la, ra))
          case None =>
            // FIXME(META) instead of inferring two side, infer one side then check another; or if we have meta with levels... can we just write a max level? it seems not that easy... because you run into the same kind of problems
            val (lt, la) = infer(left)
            val (rt, ra) = infer(right)
            val ttt = if (subTypeOf(lt, rt)) {
              Some(rt)
            } else if (subTypeOf(rt, lt)) {
              Some(lt)
            } else None
            ttt match {
              case Some(t) =>
                val ctx = newDimensionLayer(Name.empty)._1
                val ta = ctx.reify(t.bestReifyValue)
                debug(s"infer path type $ta", 0)
                (Value.Universe(ctx.cinferLevel(ta)), Abstract.PathType(dbi.Closure(Seq.empty, ta), la, ra))
              case None =>
                throw ElaboratorException.InferPathEndPointsTypeNotMatching()
            }
        }
      case p: Concrete.PatternLambda =>
        throw ElaboratorException.CannotInferReturningTypeWithPatterns()
      case l: Concrete.Lambda =>
        if (l.ensuredPath) {
          assert(!l.imps)
          // @syntax_creation
          val (ta, va) = inferTelescope(Seq((false, l.name, Concrete.I)), None, l.body)
          (eval(ta), va)
        } else {
          throw ElaboratorException.CannotInferLambda()
        }
      case Concrete.App(lambda, arguments) =>
        inline def defaultCase(lt: Value, la: Abstract) = {
          val (v1, v2) = inferApp(lt, la, arguments)
          reduceMore(v1, v2) // because inferApp stops when arguments is finished
        }
        lambda match {
          case Concrete.App(l2, a2) =>
            // @syntax_creation
            infer(Concrete.App(l2, a2 ++ arguments))
          case Concrete.Projection(left, right) =>
            inferProjectionApp(left, right, arguments)
          case Concrete.Reference(name) =>
            lookupTerm(name, 0) match {
              case NameLookupResult.Typed(typ, ref) => defaultCase(typ, ref)
              case NameLookupResult.Construct(self, index, closure) =>
                (self, checkConstructApp(self, index, closure, arguments))
            }
          case _ =>
            val (lt, la) = infer(lambda, true)
            defaultCase(lt, la)
        }
      case Concrete.Projection(left, right) =>
        inferProjectionApp(left, right, Seq.empty)
      case Concrete.GlueType(ty, faces) =>
        val (lv, ta) = inferLevel(ty)
        val tv = eval(ta)
        val facesA = faces.map(f => {
          val formula = checkAndEvalFormula(f.dimension)
          val ba = doForValidFormulaOrThrow(formula._1, asgn => {
            val ctx = newSyntaxDirectedRestrictionLayer(asgn).newDimensionLayer(Name.empty)._1 // this is currently a hack!
            // TODO here we checked by same level as ty.
            val bd = ctx.check(f.term, Value.App(BuiltIn.equiv_of, tv).restrict(asgn))
            dbi.Closure(ctx.finish(), bd)
          })
          (formula._2, ba)
        }).toMap
        (Value.Universe(lv), Abstract.GlueType(ta, facesA))
      case Concrete.Unglue(m) =>
        val (mt, ma) = infer(m)
        mt.whnf match {
          case Value.GlueType(ty, faces) =>
            (ty, Abstract.Unglue(reify(ty.bestReifyValue), ma, false, reifyEnclosedSystem(faces.view.mapValues(a => () => a().bestReifyValue).toMap)))
          case _ => throw ElaboratorException.UnglueCannotInfer()
        }
      case Concrete.Let(declarations, in) =>
        val (ctx, ms, da) = newDefinesLayer().checkDeclarations(declarations, false)
        val (it, ia) = ctx.infer(in)
        val ms0 = ctx.freeze()
        (it, Abstract.Let(ms ++ ms0, da, ia))
    }
    debug(s"infer result ${res._2}")
    res
  }

  private def checkAndEvalFormula(r: Concrete): (semantic.Formula, dbi.Formula) = {
    val a = checkFormula(r)
    (eval(a), a)
  }
  // it always returns formulas with assignments inlined
  private def checkFormula(r: Concrete): dbi.Formula = {
    r match {
      case Concrete.Reference(name) =>
         lookupDimension(name).ref
      case Concrete.And(left, right) =>
        val l = checkFormula(left)
        val r = checkFormula(right)
        dbi.Formula.And(l, r)
      case Concrete.Or(left, right) =>
        val l = checkFormula(left)
        val r = checkFormula(right)
        dbi.Formula.Or(l, r)
      case Concrete.Neg(a) =>
        val r = checkFormula(a)
        dbi.Formula.Neg(r)
      case Concrete.True =>
        dbi.Formula.True
      case Concrete.False =>
        dbi.Formula.False
      case _ => throw ElaboratorException.ExpectingFormula()
    }
  }

  private def inferFlatLevel(fs: Concrete.NameType.FlatSeq): (Int, Seq[(Name, Boolean, dbi.Closure)], Self) = {
    var ctx = this
    var l = 0
    // disallow name shadowing in same concrete flat seq
    if (fs.map(_._2).toSet.size != fs.size) throw ElaboratorException.AlreadyDeclared()
    val fas = fs.map(n => {
      val (fl, fa) = ctx.inferLevel(n._3)
      l = l max fl
      val ms = ctx.freeze()
      val (ctx1, g) = ctx.newParameter(n._2, ctx.eval(fa))
      ctx = ctx1
      (n._2, n._1, dbi.Closure(ms, fa))
    })
    (l, fas, ctx)
  }


  private def inferTelescope(domain: NameType.FlatSeq, codomain: Option[Concrete], body: Concrete): (Abstract, Abstract) = {
    domain match {
      case head +: tail =>
        head._3 match {
          case Concrete.I =>
            if (head._1) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
            val ctx = newDimensionLayer(head._2)._1
            val (ta, va) = ctx.inferTelescope(tail, codomain, body)
            val ms = ctx.finish()
            val cloa = dbi.Closure(ms, va)
            val clov = evalAbsClosure(cloa)
            val left = clov(semantic.Formula.False)
            val right = clov(semantic.Formula.True)
            (Abstract.PathType(dbi.Closure(ms, ta), reify(left.bestReifyValue), reify(right.bestReifyValue)), Abstract.PathLambda(cloa))
          case _ =>
            val (_, da) = inferLevel(head._3)
            val ctx = newParameterLayer(head._2, eval(da))._1
            val (ta, va) = ctx.inferTelescope(tail, codomain, body)
            val ms = ctx.finish()
            (Abstract.Function(da, head._1, dbi.Closure(ms, ta)), Abstract.Lambda(dbi.Closure(ms, va)))
        }
      case Seq() =>
        codomain match {
          case Some(value) =>
            val (_, a) = inferLevel(value)
            (a, check(body, eval(a)))
          case None =>
            val (bt, ba) = infer(body)
            val btr = reify(bt.bestReifyValue)
            debug(s"infer domain $btr", 1)
            (btr, ba)
        }
    }
  }

  private def inferTelescope(domain: NameType.FlatSeq, codomain: Concrete): (Int, Abstract) = {
    domain match {
      case head +: tail =>
        head._3 match {
          case Concrete.I =>
            throw ElaboratorException.CannotInferPathTypeWithoutBody()
          case _ =>
            val (dl, da) = inferLevel(head._3)
            val ctx = newParameterLayer(head._2, eval(da))._1
            val (cl, ca) = ctx.inferTelescope(tail, codomain)
            (dl max cl, Abstract.Function(da, head._1, dbi.Closure(ctx.finish(), ca)))
        }
      case Seq() =>
        val (l, a) = inferLevel(codomain)
        (l, a)
    }
  }

  private def inferGraphValuesPart(nodes: semantic.ClosureGraph, arguments: Seq[(Boolean, Concrete)], accumulator: Seq[(Abstract, Value)] = Seq.empty): Seq[(Abstract, Value)] = {
    val i = accumulator.size
    def implicitCase() = {
      val (mv, ma) = newMeta(nodes(i).independent.typ)
      val ns = nodes.reduce(i, mv)
      inferGraphValuesPart(ns, arguments, accumulator.:+((ma, mv)))
    }
    arguments match {
      case head +: tail => // more arguments
        if (i >= nodes.size) {
          throw ElaboratorException.ConstructorWithMoreArguments()
        } else {
          val imp = nodes(i).implicitt
          if (imp == head._1) { // this is a given argument
            val aa = check(head._2, nodes(i).independent.typ)
            val av = eval(aa)
            val ns = nodes.reduce(i, av)
            inferGraphValuesPart(ns, tail, accumulator.:+((aa, av)))
          } else if (imp) { // this is a implicit argument not given
            implicitCase()
          } else {
            throw ElaboratorException.NotExpectingImplicitArgument()
          }
        }
      case Seq() =>
        if (i == nodes.size) { // no argument and no field, perfect!
          accumulator
        } else {
          if (nodes(i).implicitt) { // more implicits, finish off
            implicitCase()
          } else { // no more implicits, we want to wrap the thing in lambda
            throw ElaboratorException.NotFullyAppliedConstructorNotSupportedYet()
          }
        }
    }
  }

  private def inferApp(lt: Value, la: Abstract, arguments: Seq[(Boolean, Concrete)]): (Value, Abstract) = {
    arguments match {
      case head +: tail =>
        lt.whnf match {
          case f@Value.Function(domain, fimp, codomain) =>
            if (fimp == head._1) {
              val aa = check(head._2, domain)
              val av = eval(aa)
              val lt1 = codomain(av)
              val la1 = Abstract.App(la, aa)
              inferApp(lt1, la1, tail)
            } else if (fimp) {
              val (lt1, la1) = finishOffImplicits(f, la)
              inferApp(lt1, la1, arguments)
            } else {
              throw ElaboratorException.NotExpectingImplicitArgument()
            }
          case Value.PathType(typ, _, _) =>
            if (head._1) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
            val da = checkFormula(head._2)
            val lt1 = typ(eval(da))
            val la1 = Abstract.PathApp(la, da)
            inferApp(lt1, la1, tail)
          // TODO user defined applications
          case _ =>
            throw ElaboratorException.UnknownAsFunction()
        }
      case Seq() =>
        (lt, la)
    }
  }

  private def check(
                     term: Concrete,
                     cp: Value,
                     lambdaNameHints: Seq[Name] = Seq.empty,
                     indState: IndState = IndState.stop
  ): Abstract = {
    debug(s"check $term")
    val (hint, tail) = lambdaNameHints match {
      case head +: tl => (head, tl)
      case _ => (Name.empty, Seq.empty)
    }
    def fallback(noReduceMore: Boolean = false): Abstract = {
      val (tt, ta) = infer(term, noReduceMore)
      if (subTypeOf(tt, cp)) ta
      else {
        info(term.toString)
        info(s"${reify(tt.whnf)}")
        info(s"${reify(cp.whnf)}")
        throw ElaboratorException.TypeMismatch()
      }
    }
    term match {
      case Concrete.Hole =>
        newMeta(cp)._2
      case Concrete.Let(declarations, bd) =>
        val (ctx, ms, da) = newDefinesLayer().checkDeclarations(declarations, false)
        val ba = ctx.check(bd, cp)
        val ms0 = ctx.freeze()
        Abstract.Let(ms ++ ms0, da, ba)
      case _ =>
        cp.whnf match {
          case Value.Function(domain, fimp, codomain) =>
            term match {
              case Concrete.Lambda(n, limp, ensuredPath, body) if fimp == limp =>
                assert(!ensuredPath)
                val (ctx, v) = newParameterLayer(n.nonEmptyOrElse(hint), domain)
                val ba = ctx.check(body, codomain(v), tail, indState.app(v))
                Abstract.Lambda(dbi.Closure(ctx.finish(), ba))
              case Concrete.PatternLambda(limp, cases) if fimp == limp =>
                val res = cases.map(c => {
                  val (ctx, v, pat) = newPatternLayer(c.pattern, domain)
                  val ba = ctx.check(c.body, codomain(v), tail)
                  dbi.Case(pat, dbi.Closure(ctx.finish(), ba))
                })
                Abstract.PatternLambda(Elaborator.pgen(), reify(domain.bestReifyValue), reify(codomain), res)
              case _ =>
                fallback(fimp)
            }
          case v@Value.Universe(l) =>
            term match {
              case s: Concrete.Sum =>
                val ind = indState.consume(l)
                checkSum(Some((v, ind, l)), s)._2
              case r: Concrete.Record =>
                val ind = indState.consume(l)
                checkRecord(ind.map(_._2), r)._2
              case _ =>
                fallback()
            }
          case Value.GlueType(ty, faces) =>
            term match {
              case Concrete.Glue(base, fs) =>
                val ba = check(base, ty)
                val bv = eval(ba)
                val phi1 = faces.phi
                val ffs = fs.map(a => { val (f1, f2) = checkAndEvalFormula(a.dimension); (f1, f2, a.term) })
                val phi2 =  ffs.map(_._1).phi
                if (phi1 == phi2) {
                  val fas = ffs.map(f => {
                    val body = doForValidFormulaOrThrow(f._1, asgn => {
                      val terms = mutable.Set.empty[dbi.Closure]
                      for (tf <- faces) {
                        tf._1.normalForm.filter(_.satisfiable).foreach(asgn2 => {
                          val asg = asgn ++ asgn2
                          if (asg.satisfiable) {
                            val ctx = newSyntaxDirectedRestrictionLayer(asg).newDimensionLayer(Name.empty)._1
                            val bd1 = tf._2.restrict(asg)()
                            val res = ctx.check(f._3, Value.Projection(bd1, 0))
                            val itemv = eval(res)
                            if (ctx.unifyTerm(ty.restrict(asg), bv.restrict(asg), Value.App(Value.Projection(bd1, 1), itemv))) {
                              terms.add(dbi.Closure(ctx.finish(), res))
                            } else {
                              throw ElaboratorException.GlueNotMatching()
                            }
                          }
                        })
                      }
                      if (terms.size != 1) {
                        logicError()
                      }
                      terms.head
                    })
                    (f._2, body)
                  }).toMap
                  Abstract.Glue(ba, fas)
                } else {
                  throw ElaboratorException.FacesNotMatching()
                }
              case _ => fallback()
            }
          case Value.PathType(typ, left, right) =>
            term match {
              case Concrete.Lambda(n, b, _, body) =>
                if (b) throw ElaboratorException.DimensionLambdaCannotBeImplicit()
                val (c1, dv) = newDimensionLayer(n.nonEmptyOrElse(hint))
                val t1 = typ(dv)
                import semantic.Formula._
                val a1 = c1.check(body, t1, tail)
                val ps = dbi.Closure(c1.finish(), a1)
                // info("path body:"); print(Abstract.PathLambda(ps))
                val pv = evalAbsClosure(ps)
                val leftEq = unifyTerm(typ(False), pv(False), left)
                val rightEq = unifyTerm(typ(True), pv(True), right)
                if (leftEq && rightEq) {
                  Abstract.PathLambda(ps)
                } else {
                  throw ElaboratorException.PathEndPointsNotMatching()
                }
              case _ => fallback()
            }
          case r: Value.Record =>
            term match {
              case Concrete.App(Concrete.Make, vs) =>
                Abstract.Make(inferGraphValuesPart(r.nodes, vs).map(_._1))
              case _ =>
                fallback()
            }
          case r: Value.Sum =>
            term match {
              case Concrete.Projection(Concrete.Hole, Concrete.Reference(name)) =>
                r.constructors.indexWhere(_.name.by(name)) match {
                  case -1 => fallback()
                  case a => checkSumConstructApp(r, a, Seq.empty)
                }
              case Concrete.App(Concrete.Projection(Concrete.Hole, Concrete.Reference(name)), as) =>
                r.constructors.indexWhere(_.name.by(name)) match {
                  case -1 => fallback()
                  case a => checkSumConstructApp(r, a, as)
                }
              case _ => fallback()
            }
          case _ => fallback()
        }
    }
  }




  // should we make sure type annotation is the minimal type?
  // ANS: we don't and we actually cannot
  private def checkDeclaration(
      s: Declaration.Single,
       topLevel: Boolean): Self = {
    // @syntax_creation
    def wrapBody(t: Concrete, imp: Seq[Boolean]): Concrete = if (imp.isEmpty) t else wrapBody(Concrete.Lambda(Name.empty, imp.last, false, t), imp.dropRight(1))
    if (s.modifiers.contains(Declaration.Modifier.__Debug)) {
      val a = 1
    }
    val ret: Elaborator = s match {
      case Declaration.Define(ms, name, ps, t0, v) =>
        // TODO implement with constructor
        //        if (ms.contains(Modifier.WithConstructor)) {
        //        }
        // a inductive type definition
        if (ms.contains(Modifier.WithoutDefine)) {
          throw ElaboratorException.ForbiddenModifier()
        }
        var inductively: IndState = IndState.stop
        def rememberInductivelyBy(ty: Abstract, self: Value) = {
          inductively = if (ms.contains(Modifier.Inductively)) {
            if (topLevel) {
              new IndState(Elaborator.igen(),ty,  false, self)
            } else {
              throw ElaboratorException.RecursiveTypesMustBeDefinedAtTopLevel()
            }
          } else IndState.stop
          inductively
        }
        val ret: Elaborator = lookupDefined(name) match {
          case Some((index, item)) =>
            if (item.isDefined) {
              throw ElaboratorException.AlreadyDefined()
            } else if (item.isAxiom) {
              throw ElaboratorException.TryingToDefineAxiom()
            }
            info(s"check defined $name")
            if (ps.nonEmpty || t0.nonEmpty) throw ElaboratorException.SeparateDefinitionCannotHaveTypesNow()
            val va = check(v, item.baseType, Seq.empty, rememberInductivelyBy(item.typCode, item.ref.base))
            // info("body:"); print(va)
            freeze()
            val (ctx, _) = newDefinitionChecked(index, name, va)
            // reevalStuff(ctx, Dependency(index, 0, DependencyType.Value))
            info(s"checked $name")
            ctx
          case None => t0 match {
            case Some(t) if !ps.exists(_.ty == Concrete.I) =>
              // term with type
              info(s"define $name")
              var time = System.currentTimeMillis()
              val pps = NameType.flatten(ps)
              val (_, ta) = inferTelescope(pps, t)
              // info("type:"); print(ta)
              freeze()
              val (ctx, index, generic) = newDeclaration(name, ta) // allows recursive definitions
              val lambdaNameHints = pps.map(_._2) ++ (t match {
                case Concrete.Function(d, _) =>
                  NameType.flatten(d).map(_._2)
                case _ => Seq.empty
              })
              val va = ctx.check(wrapBody(v, pps.map(_._1)), generic.typ, lambdaNameHints, rememberInductivelyBy(ta, generic))
              // info("body:"); print(va)
              ctx.freeze()
              val (ctx2, ref) = ctx.newDefinitionChecked(index, name, va)
              // some definition is specially treated, they are defined in code, but we need to reference them in evaluator.
              // these definition should not have re-eval behaviour.
              // TODO add a primitive modifier so that no error happens with this
              if (name == Name(Text("fiber_at"))) {
                assert(BuiltIn.fiber_at == null)
                BuiltIn.fiber_at = ref
              } else if (name == Name(Text("equiv"))) {
                assert(BuiltIn.equiv == null)
                BuiltIn.equiv = ref
              } else if (name == Name(Text("equiv_of"))) {
                assert(BuiltIn.equiv_of == null)
                BuiltIn.equiv_of = ref
              } else if (name == Name(Text("path_to_equiv"))) {
                assert(BuiltIn.path_to_equiv == null)
                BuiltIn.path_to_equiv = ref
              }

              time = System.currentTimeMillis() - time
              val timeStr = if (time > 10) s" in ${time}" else ""
              info(s"defined $name" + timeStr)
              ctx2
            case _ =>
              // term without type
              info(s"infer $name")
              val (ta, va) = inferTelescope(NameType.flatten(ps), t0, v)
              // info("type:" + ta)
              // info("body:"); print(va)
              freeze()
              val (ctx, index, generic) = newDefinition(name, ta, va)
              info(s"inferred $name")
              ctx
          }
        }
        if (!inductively.stop) throw ElaboratorException.InductiveModifierNotApplyable()
        ret
      case Declaration.Declare(ms, name, ps, t) =>
        lookupDefined(name) match {
          case Some(_) =>
            throw ElaboratorException.AlreadyDeclared()
          case None =>
            info(s"declare $name")
            if (ms.exists(_ != Modifier.WithoutDefine)) throw ElaboratorException.ForbiddenModifier()
            val isAxiom = ms.contains(Modifier.WithoutDefine)
            if (isAxiom && !topLevel) {
              throw ElaboratorException.AxiomCanOnlyBeInTopLevel()
            }
            val (_, ta) = inferTelescope(NameType.flatten(ps), t)
            // info("type:"); print(ta)
            freeze()
            val (ctx, index, generic) = newDeclaration(name, ta, isAxiom)
            info(s"declared ${if (isAxiom) "axiom " else ""}$name")
            ctx
        }
    }
    if (s.modifiers.contains(Declaration.Modifier.__Debug)) {
      Value.NORMAL_FORM_MODEL = true
      val a = ret.layers.head.asInstanceOf[Layer.Defines].terms.find(_.name == s.name).get.ref.base.value
      val time  = System.currentTimeMillis()
      println(reify(a.nf))
      // val nf = a.whnf.asInstanceOf[PathLambda].body(semantic.Formula.Generic(-1)).whnf
      // val fs = nf.asInstanceOf[Value.Hcomp].faces
      // val pair = fs.toSeq.head
      // val res = pair._2(semantic.Formula.Generic(-2)).restrict(pair._1.normalForm.head).whnf
      // println(res)
      println(s"DEBUG used time: ${System.currentTimeMillis() - time}")
      Value.NORMAL_FORM_MODEL = false
    }
    ret
  }



  private def checkDeclarations(seq0: Seq[Declaration], topLevel: Boolean): (Self, Seq[Abstract], Seq[Abstract]) = {
    // how to handle mutual recursive definitions, calculate strong components
    def flatten(d: Declaration, ps: Seq[NameType]): Seq[Declaration.Single] = d match {
      case d: Declaration.Define =>
        Seq(d.copy(parameters = ps ++ d.parameters))
      case d: Declaration.Declare =>
        Seq(d.copy(parameters = ps ++ d.parameters))
      case Declaration.Parameters(parameters, items) =>
        items.flatMap(a => flatten(a, ps ++ parameters))
    }
    val seq = seq0.flatMap(a => flatten(a, Seq.empty))
    var ctx = this
    for (s <- seq) {
      val ctx0 = ctx.checkDeclaration(s, topLevel)
      ctx = ctx0
    }
    val layer = ctx.layers.head.asInstanceOf[Layer.Defines]
    if (layer.metas.metas.exists(_.code == null)) {
      logicError()
    }
    if (layer.terms.exists(a => !a.isDefined && !a.isAxiom)) {
      throw ElaboratorException.DeclarationWithoutDefinition()
    }
    (ctx, layer.metas.metas.map(_.code).toSeq, layer.terms.map(_.code))
  }



  private def inferLevel(term: Concrete): (Int, Abstract) = {
    val (tt, ta) = infer(term)
    tt.whnf match {
      case Value.Universe(l) => (l, ta)
      // TODO user defined type coercion
      case _ =>
        throw ElaboratorException.UnknownAsType()
    }
  }


  def check(m: Module): Elaborator = Benchmark.TypeChecking {
    checkDeclarations(m.declarations, true)._1
  }
}
