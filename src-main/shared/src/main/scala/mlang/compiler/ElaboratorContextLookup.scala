package mlang.compiler


import mlang.utils._

import scala.collection.mutable

sealed trait ElaboratorContextLookupException extends CompilerException

object ElaboratorContextLookupException {
  case class NonExistingReference(name: Text) extends Exception(s"Non existing reference $name") with ElaboratorContextLookupException
  case class ReferenceSortWrong(name: Text) extends ElaboratorContextLookupException
}

trait ElaboratorContextLookup extends ElaboratorContextBase with ElaboratorContextRebind {

  def lookupTerm(name: Text): (Value, Abstract) = {
    lookup0(name) match {
      case (t: Value, j: Abstract) =>
        (t, j)
      case _ =>
        throw ElaboratorContextLookupException.ReferenceSortWrong(name)
    }
  }


  def lookupDimension(name: Text): Abstract.Formula.Reference = {
    lookup0(name) match {
      case (_: String, j: Abstract.Formula.Reference) =>
        j
      case _ =>
        throw ElaboratorContextLookupException.ReferenceSortWrong(name)
    }
  }

  private def lookup0(name: Text): (Object, Object) = {
    var up = 0
    var ls = layers
    var binder: (Object, Object) = null
    val faces = mutable.ArrayBuffer[Value.Formula.Assignment]()
    var isGlobalDefinition = false
    while (ls.nonEmpty && binder == null) {
      var i = 0
      ls.head match {
        case ly: Layer.Parameters =>
          var ll = ly.binders
          var index = -1
          while (ll.nonEmpty && binder == null) {
            if (ll.head.name.by(name)) {
              index = i
              binder = (ll.head.typ,
                  Abstract.Reference(up, index))
            }
            i += 1
            ll = ll.tail
          }
        case ly: Layer.Defines =>
          var ll = ly.terms
          var index = -1
          while (ll.nonEmpty && binder == null) {
            if (ll.head.name.by(name)) {
              index = i
              binder = (ll.head.typ,
                  Abstract.Reference(up, index))
              isGlobalDefinition = ls.size == 1 // FIXME maybe this should be better
            }
            i += 1
            ll = ll.tail
          }
        case p:Layer.Parameter =>
          if (p.binder.name.by(name)) {
            binder = (p.binder.typ, Abstract.Reference(up, -1))
          }
        case d: Layer.Dimension =>
          if (d.name.by(name)) {
            binder = ("", Abstract.Formula.Reference(up))
          }
        case l: Layer.Restriction =>
          l.res match {
            case Left(value) =>
              faces.appendAll(value)
            case _ =>
              logicError()
          }
      }
      if (binder == null) {
        ls = ls.tail
        up += 1
      }
    }
    val rs = faces.toSet
    if (debug.enabled) assert(Value.Formula.satisfiable(rs))
    if (binder == null) {
      throw ElaboratorContextLookupException.NonExistingReference(name)
    } else {
      binder match {
        case (t: Value, j: Abstract) =>
          if (isGlobalDefinition) {
            (t, j)
          } else {
            (t.restrict(rs), j)
          }
        case (a: String, j: Abstract.Formula.Reference) =>
          (a, j)
        case _ => logicError()
      }
    }
  }
}

