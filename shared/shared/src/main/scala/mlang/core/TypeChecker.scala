package mlang.core

import mlang.concrete.{Pattern => Patt, _}
import Context._
import mlang.name._
import mlang.utils._

import scala.collection.mutable
import scala.language.implicitConversions




sealed trait TypeCheckException extends CoreException

object TypeCheckException {


  // syntax
  case class FieldsDuplicated() extends TypeCheckException
  case class TagsDuplicated() extends TypeCheckException
  case class MustBeNamed() extends TypeCheckException
  case class EmptyTelescope() extends TypeCheckException
  case class EmptyArguments() extends TypeCheckException
  case class EmptyLambdaParameters() extends TypeCheckException

  // elimination mismatch
  case class UnknownAsType() extends TypeCheckException
  case class UnknownProjection() extends TypeCheckException
  case class UnknownAsFunction() extends TypeCheckException
  case class UnknownAsPathType() extends TypeCheckException

  case class CheckingAgainstNonFunction() extends TypeCheckException

  case class CannotInferLambda() extends TypeCheckException
  case class CannotInferReturningTypeWithPatterns() extends TypeCheckException


  case class TypeMismatch() extends TypeCheckException

  case class ForbiddenModifier() extends TypeCheckException

  case class DeclarationWithoutDefinition(name: Name) extends TypeCheckException

  case class ExpectingDimension() extends TypeCheckException

  case class PathEndPointsNotMatching() extends TypeCheckException
  case class InferPathEndPointsTypeNotMatching() extends TypeCheckException

  case class ExpectingLambdaTerm() extends TypeCheckException

  case class RemoveFalseFace() extends TypeCheckException
  case class CapNotMatching() extends TypeCheckException
  case class FacesNotMatching() extends TypeCheckException

  case class RequiresValidRestriction() extends TypeCheckException
}


object TypeChecker {
  private val pgen = new LongGen.Positive()
  val empty = new TypeChecker(Seq(Layer.Terms(Seq.empty)))
}

class TypeChecker private (protected override val layers: Layers)
    extends ContextBuilder with BaseEvaluator with PlatformEvaluator with Reifier {
  override type Self = TypeChecker

  override protected implicit def create(a: Layers): Self = new TypeChecker(a)


  def checkLine(a: Term): (Value.AbsClosure, Abstract.AbsClosure) = {
    a match {
      case Term.Lambda(n, body) =>
        val ctx = newDimensionLayer(n.getOrElse(Name.empty))._1
        val (_, ta) = ctx.inferLevel(body)
        val tv = eval(Abstract.PathLambda(ta))
        (tv.asInstanceOf[Value.PathLambda].body, ta)
      case _ => throw TypeCheckException.ExpectingLambdaTerm()
    }
  }

  def checkValidRestrictions(ds: Seq[Value.DimensionPair]) = {
    val res = ds.exists(a => a.isTrue) || ds.flatMap(r => ds.map(d => (r, d))).exists(p => {
      p._1.from == p._2.from && !p._1.from.isConstant &&
          p._1.to.isConstant && p._2.to.isConstant && p._1.to != p._2.to
    })
    if (!res) throw TypeCheckException.RequiresValidRestriction()
  }

  def checkCompatibleCapAndFaces(
      ident: Name.Opt,
      faces: Seq[Term.Face],
      bt: Value.AbsClosure,
      bv: Value,
      dv: Value.DimensionPair
  ): Seq[Abstract.Face] = {
    // we use this context to evaluate body of faces, it is only used to keep the dimension binding to the same
    // one, but as restricts is already present in abstract terms, it is ok to use this instead of others
    val (fContext, dim0) = newTermsLayer().newDimensionLayer(ident.getOrElse(Name.empty))
    val btt = bt(dim0)
    val res = faces.map(a => {
      val (dav, daa) = checkDimensionPair(a.dimension)
      if (dav.isFalse) {
        throw TypeCheckException.RemoveFalseFace()
      } else {
        val ctx0 = newRestrictionLayer(dav)
        val (ctx, fd) = ctx0.newDimensionLayer(ident.getOrElse(Name.empty))
        val btr = bt(fd).restrict(dav)
        val na = ctx.check(a.term, btr)
        val nv = ctx0.newDimensionLayer(ident.getOrElse(Name.empty), dv.from).eval(na)
        if (!Conversion.equalTerm(btr, bv.restrict(dav), nv)) {
          throw TypeCheckException.CapNotMatching()
        }
        (Abstract.Face(daa, na), fContext.eval(na), dav, ctx0: Self)
      }
    })
    for (i <- faces.indices) {
      val l = res(i)
      for (j <- 0 until i) {
        val r = res(j)
        val rj = faces(j)
        // this might evaluate the dimensions to new values
        val (dfv, _) = l._4.checkDimensionPair(rj.dimension)
        // only used to test if this restriction is false face or not
        if (!dfv.isFalse) {
          Conversion.equalTerm(
            btt.restrict(l._3).restrict(dfv),
            l._2.restrict(dfv),
            r._2.restrict(l._3))
        }
      }
    }
    val ds = res.map(_._3.sorted)
    checkValidRestrictions(ds)
    res.map(_._1)
  }


  private def infer(term: Term): (Value, Abstract) = {
    debug(s"infer $term")
    val res = term match {
      case Term.Universe(i) =>
        (Value.Universe(i + 1), Abstract.Universe(i))
      case Term.Reference(name) =>
        // should lookup always return a value? like a open reference?
        val (binder, abs) = lookupTerm(name)
        (binder, abs)
      case Term.ConstantDimension(_) =>
        throw ContextException.ConstantSortWrong()
      case Term.Cast(v, t) =>
        val (_, ta) = inferLevel(t)
        val tv = eval(ta)
        (tv, check(v, tv))
      case Term.Function(domain, codomain) =>
        if (domain.isEmpty) throw TypeCheckException.EmptyTelescope()
        val (l, v) = inferTelescope(NameType.flatten(domain), codomain)
        (Value.Universe(l), v)
      case r@Term.Record(fields) =>
        for (f <- fields) {
          if (f.names.isEmpty) throw TypeCheckException.MustBeNamed()
        }
        for (i <- r.names.indices) {
          for (j <- (i + 1) until r.names.size) {
            if (r.names(i) intersect r.names(j)) {
              throw TypeCheckException.FieldsDuplicated()
            }
          }
        }
        val (fl, fs) = newTermsLayer().inferFlatLevel(fields)
        val ns = fs.map(pair => Abstract.RecordNode(pair._1, pair._2.dependencies(0).toSeq.sorted, pair._2))
        (Value.Universe(fl), Abstract.Record(fl, ns))
      case Term.Sum(constructors) =>
        for (i <- constructors.indices) {
          for (j <- (i + 1) until constructors.size) {
            if (constructors(i).name == constructors(j).name) {
              throw TypeCheckException.TagsDuplicated()
            }
          }
        }
        // TODO in case of HIT, each time a constructor finished, we need to construct a partial sum and update the value
        val fs = constructors.map(c => newTermsLayer().inferFlatLevel(c.term))
        val fl = fs.map(_._1).max
        (Value.Universe(fl), Abstract.Sum(fl, fs.map(_._2.map(_._2)).zip(constructors).map(a => Abstract.Constructor(a._2.name, a._1))))
      case Term.Coe(direction, tp, base) =>
        val (dv, da) = checkDimensionPair(direction)
        val (cl, ta) = checkLine(tp)
        val la = check(base, cl(dv.from))
        (cl(dv.to), Abstract.Coe(da, ta, la))
      case Term.Com(direction, tp, base, ident, faces) =>
        val (dv, da) = checkDimensionPair(direction)
        val (cl, ta) = checkLine(tp)
        val ba = check(base, cl(dv.from))
        val rs = checkCompatibleCapAndFaces(ident, faces, cl, eval(ba), dv)
        (cl(dv.to), Abstract.Com(da, ta, ba, rs))
      case Term.Hcom(direction, base, ident, faces) =>
        val (dv, da)= checkDimensionPair(direction)
        val (bt, ba) = infer(base)
        val bv = eval(ba)
        val rs = checkCompatibleCapAndFaces(ident, faces, Value.AbsClosure(bt), bv, dv)
        (bt, Abstract.Hcom(da, reify(bt), ba, rs))
      case Term.PathType(typ, left, right) =>
        typ match {
          case Some(tp) =>
            tp match {
              case Term.Lambda(name, body) =>
                val ctx = newDimensionLayer(name.getOrElse(Name.empty))._1
                val (tl, ta) = ctx.inferLevel(body)
                val tv = eval(Abstract.PathLambda(ta)).asInstanceOf[Value.PathLambda]
                val la = check(left, tv.body(Value.Dimension.False))
                val ra = check(right, tv.body(Value.Dimension.True))
                (Value.Universe(tl), Abstract.PathType(ta, la, ra))
              case _ => throw TypeCheckException.ExpectingLambdaTerm()
            }
          case None =>
            val (lt, la) = infer(left)
            val (rt, ra) = infer(right)
            if (Conversion.equalType(lt, rt)) {
              val ta = newTermsLayer().reify(lt)
              if (debug.enabled) debug(s"infer path type: $ta")
              (Value.Universe(Value.inferLevel(lt)), Abstract.PathType(ta, la, ra))
            } else {
              throw TypeCheckException.InferPathEndPointsTypeNotMatching()
            }
        }
      case Term.PatternLambda(_) =>
        throw TypeCheckException.CannotInferReturningTypeWithPatterns()
      case Term.Lambda(_, _) =>
        throw TypeCheckException.CannotInferLambda()
      case Term.Projection(left, right) =>
        val (lt, la) = infer(left)
        val lv = eval(la)
        def ltr = lt.asInstanceOf[Value.Record]
        def error() = throw TypeCheckException.UnknownProjection()
        lv.whnf match {
          case m: Value.Make if ltr.nodes.exists(_.name.by(right)) =>
            val index = ltr.nodes.indexWhere(_.name.by(right))
            (ltr.projectedType(m.values, index), Abstract.Projection(la, index))
          // TODO user defined projections
          case r: Value.Record if right == Ref.make =>
            (r.makerType, Abstract.Maker(la, -1))
          case r: Value.Sum if r.constructors.exists(_.name == right) =>
            r.constructors.find(_.name == right) match {
              case Some(br) =>
                (br.makerType, Abstract.Maker(la, r.constructors.indexWhere(_.name == right)))
              case _ => error()
            }
          case _ => error()
        }
      case Term.App(lambda, arguments) =>
        if (arguments.isEmpty) throw TypeCheckException.EmptyArguments()
        val (lt, la) = infer(lambda)
        inferApp(lt, la, arguments)
      case Term.Let(declarations, in) =>
        val (ctx, da, order) = newTermsLayer().checkDeclarations(declarations)
        val (it, ia) = ctx.infer(in)
        (it, Abstract.Let(da, order.flatten, ia))

    }
    debug(s"infer result ${res._2}")
    res
  }

  private def checkDimensionPair(p: Term.Pair): (Value.DimensionPair, Abstract.DimensionPair) = {
    val (a, b) = checkDimension(p.from)
    val (c, d) = checkDimension(p.to)
    (Value.DimensionPair(a, c), Abstract.DimensionPair(b, d))
  }

  private def checkDimension(r: Term): (Value.Dimension, Abstract.Dimension) = {
    r match {
      case Term.Reference(name) =>
        val (v, a) = lookupDimension(name)
        (v, a)
      case Term.ConstantDimension(i) =>
        if (i) {
          (Value.Dimension.True, Abstract.Dimension.True)
        } else {
          (Value.Dimension.False, Abstract.Dimension.False)
        }
      case _ => throw TypeCheckException.ExpectingDimension()
    }
  }

  private def inferTelescope(domain: NameType.FlatSeq, codomain: Term): (Int, Abstract) = {
    domain match {
      case head +: tail =>
        val (dl, da) = inferLevel(head._2)
        val ctx = head._1 match {
          case Some(n) =>
            newTermLayer(n, eval(da))._1
          case _ => newTermsLayer()
        }
        val (cl, ca) = ctx.inferTelescope(tail, codomain)
        (dl max cl, Abstract.Function(da, ca))
      case Seq() =>
        val (l, a) = inferLevel(codomain)
        (l, a)
    }
  }

  private def inferApp(lt: Value, la: Abstract, arguments: Seq[Term]): (Value, Abstract) = {
    arguments match {
      case head +: tail =>
        lt.whnf match {
          case Value.Function(domain, codomain) =>
            val aa = check(head, domain)
            val av = eval(aa)
            val lt1 = codomain(av)
            val la1 = Abstract.App(la, aa)
            inferApp(lt1, la1, tail)
          case Value.PathType(typ, _, _) =>
            val (dv, da) = checkDimension(head)
            val lt1 = typ(dv)
            val la1 = Abstract.PathApp(la, da)
            inferApp(lt1, la1, tail)
          // TODO user defined applications
          case _ => throw TypeCheckException.UnknownAsFunction()
        }
      case Seq() =>
        (lt, la)
    }
  }

  private def hintCodomain(hint: Option[Abstract]):Option[Abstract] = hint match {
    case Some(Abstract.Function(_, b)) => Some(b)
    case _ => None
  }

  private def check(
      term: Term,
      cp: Value,
      lambdaNameHints: Seq[Name.Opt] = Seq.empty,
      lambdaFunctionCodomainHint: Option[Abstract] = None
  ): Abstract = {
    debug(s"check $term")
    val (hint, tail) = lambdaNameHints match {
      case head +: tl => (head, tl)
      case _ => (None, Seq.empty)
    }
    val res = term match {
      case Term.Lambda(n, body) =>
        cp.whnf match {
          case Value.Function(domain, codomain) =>
            val (ctx, v) = newTermLayer(n.orElse(hint).getOrElse(Name.empty), domain)
            val ba = ctx.check(body, codomain(v), tail, hintCodomain(lambdaFunctionCodomainHint))
            Abstract.Lambda(ba)
          case Value.PathType(typ, left, right) =>
            val (c1, dv) = newDimensionLayer(n.getOrElse(Name.empty))
            val t1 = typ(dv)
            import Value.Dimension._
            val a1 = c1.check(body, t1)
            val ps = Abstract.PathLambda(a1)
            val pv = eval(ps)
            val leftEq = Conversion.equalTerm(typ(False), pv.papp(False), left)
            val rightEq = Conversion.equalTerm(typ(True), pv.papp(True), right)
            if (leftEq && rightEq) {
              ps
            } else {
              throw TypeCheckException.PathEndPointsNotMatching()
            }
          case _ => throw TypeCheckException.CheckingAgainstNonFunction()
        }
      case Term.PatternLambda(cases) =>
        cp.whnf match {
          case Value.Function(domain, codomain) =>
            Abstract.PatternLambda(TypeChecker.pgen(), lambdaFunctionCodomainHint.getOrElse(reifyClosure(domain, codomain)), cases.map(c => {
              val (ctx, v, pat) = newAbstractionsLayer(c.pattern, domain)
              val ba = ctx.check(c.body, codomain(v), tail, hintCodomain(lambdaFunctionCodomainHint))
              Abstract.Case(pat, ba)
            }))
          case _ => throw TypeCheckException.CheckingAgainstNonFunction()
        }
      case _ =>
        val (tt, ta) = infer(term)
        if (Conversion.equalType(tt, cp)) ta
        else throw TypeCheckException.TypeMismatch()
    }
    debug(s"check result $res")
    res
  }

  private def reifyClosure(domain: Value, codomain: Value.Closure): Abstract = {
    val (ctx, v) = newTermLayer(Name.empty, domain)
    ctx.reify(codomain(v))
  }

  private def checkDeclaration(s: Declaration, abs: mutable.ArrayBuffer[DefinitionInfo]): Self = {
    def wrapBody(t: Term, n: Int): Term = if (n == 0) t else wrapBody(Term.Lambda(None, t), n - 1)
    s match {
      case Declaration.Define(ms, name, ps, t0, v) =>
        if (ms.contains(Declaration.Modifier.__Debug)) {
          val a = 1
        }
        t0 match {
          case Some(t) =>
            info(s"check define $name")
            val pps = NameType.flatten(ps)
            val (_, ta) = inferTelescope(pps, t)
            val tv = eval(ta)
            val lambdaNameHints = pps.map(_._1) ++(t match {
              case Term.Function(d, _) =>
                NameType.flatten(d).map(_._1)
              case _ => Seq.empty
            })
            val ctx = newDeclaration(name, tv) // allows recursive definitions
          val va = ctx.check(wrapBody(v, pps.size), tv, lambdaNameHints, hintCodomain(Some(ta)))
            info(s"declared $name")
            abs.append(DefinitionInfo(name, tv, va, Some(ta)))
            ctx
          case None =>
            if (ps.nonEmpty) ???
            val index = headTerms.indexWhere(_.name == name)
            if (index < 0) {
              val (vt, va) = infer(v)
              val ctx = newDeclaration(name, vt)
              info(s"declared $name")
              abs.append(DefinitionInfo(name, vt, va, None))
              ctx
            } else {
              val b = headTerms(index)
              val va = check(v, b.typ, Seq.empty, hintCodomain(abs(index).typCode))
              info(s"declared $name")
              abs.update(index, DefinitionInfo(name, b.typ, va, abs(index).typCode))
              this
            }
        }
      case Declaration.Declare(ms, name, ps, t) =>
        info(s"check declare $name")
        if (ms.nonEmpty) throw TypeCheckException.ForbiddenModifier()
        val (_, ta) = inferTelescope(NameType.flatten(ps), t)
        val tv = eval(ta)
        val ctx = newDeclaration(name, tv)
        info(s"declared $name")
        abs.append(DefinitionInfo(name, tv, null, Some(ta)))
        ctx
    }

  }

  private def checkDeclarations(seq: Seq[Declaration]): (Self, Seq[Abstract], Seq[Set[Int]]) = {
    // how to handle mutual recursive definitions, calculate strong components
    var ctx = this
    val abs = new mutable.ArrayBuffer[DefinitionInfo]()
    val definitionOrder = new mutable.ArrayBuffer[Set[Int]]()
    for (s <- seq) {
      if (s.modifiers.contains(Declaration.Modifier.Ignored)) {
        ctx.checkDeclaration(s, abs.clone())
      } else {
        ctx = ctx.checkDeclaration(s, abs)
      }
      val toCompile = mutable.ArrayBuffer[Int]()
      for (i <- abs.indices) {
        val t = abs(i)
        if (t.code != null && t.value.isEmpty && t.directDependencies.forall(j => abs(j).code != null)) {
          toCompile.append(i)
        }
      }
      if (toCompile.nonEmpty) {
        val toCal = toCompile.map(i => i -> abs(i).directDependencies.filter(a => toCompile.contains(a))).toMap
        val ccc = mlang.utils.graphs.tarjanCcc(toCal).toSeq.sortWith((l, r) => {
          l.exists(ll => r.exists(rr => abs(ll).directDependencies.contains(rr)))
        }).reverse
        for (c <- ccc) {
          assert(c.nonEmpty)
          definitionOrder.append(c)
          if (c.size == 1 && !abs(c.head).directDependencies.contains(c.head)) {
            val g = abs(c.head)
            val v = ctx.eval(g.code)
            g.value = Some(v)
            ctx = ctx.newDefinitionChecked(c.head, g.name, v)
            info(s"defined ${g.name}")
            if (debug.enabled) {
              val abs = reify(v)
              assert(Conversion.equalTerm(ctx.lookupTerm(g.name.refSelf)._1, eval(abs), v))
            }
          } else {
            for (i <- c) {
              abs(i).code.markRecursive(0, c)
            }
            val vs = ctx.evalMutualRecursive(c.map(i => (i, abs(i).code)).toMap)
            for (v <- vs) {
              val ab = abs(v._1)
              ab.value = Some(v._2)
              val name = ab.name
              ctx = ctx.newDefinitionChecked(v._1, name, v._2)
              info(s"defined recursively $name")
              if (debug.enabled) {
                val abd = reify(v._2)
                assert(Conversion.equalTerm(ctx.lookupTerm(ab.name.refSelf)._1, eval(abd), v._2))
              }
            }
          }
        }
      }
    }
    assert(abs.size == ctx.headTerms.size)
    abs.foreach(f => {
      if (f.code == null) {
        throw TypeCheckException.DeclarationWithoutDefinition(f.name)
      }
    })
    (ctx, abs.map(a => a.code), definitionOrder)
  }


  private def inferFlatLevel(terms: Seq[NameType]): (Int, Seq[(Name, Abstract)]) = {
    var ctx = this
    var l = 0
    val fas = terms.flatMap(f => {
      val fs = if (f.names.isEmpty) Seq(Name.empty) else f.names
      fs.map(n => {
        val (fl, fa) = ctx.inferLevel(f.ty)
        l = l max fl
        ctx = ctx.newAbstraction(n, ctx.eval(fa))._1
        (n, fa)
      })
    })
    (l, fas)
  }

  private def inferLevel(term: Term): (Int, Abstract) = {
    val (tt, ta) = infer(term)
    tt.whnf match {
      case Value.Universe(l) => (l, ta)
      // TODO user defined type coercion
      case _ => throw TypeCheckException.UnknownAsType()
    }
  }


  def check(m: Module): TypeChecker = Benchmark.TypeChecking {
    checkDeclarations(m.declarations)._1
  }
}

private case class DefinitionInfo(
    name: Name,
    typ: Value,
    code: Abstract,
    typCode: Option[Abstract],
    var value: Option[Value] = None,
   ) {
   lazy val directDependencies: Set[Int] = code.dependencies(0)
}


