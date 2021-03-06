# Roadmap

* smaller TODO
    * elaboration for partial application of constructors
    * support pasring int literal `7i` maybe? and support number literals in patterns
    * fix the `parameters` keyword, it should be like Agda where the parameters is uniformly introduced
    * support boundry expression in faces, support non-
    * check etype when using subtypingOf, but allow change etype when cast
    * seperate simple concrete sort error exceptions out

* I am thinking to do structural editors!
    * start with a simple syntax one sort of terms, and only do reference, app and projection

**bolded items** are what we want to work on next

* `DONE` totally unsafe MLTT basics
    * basic `.poor` syntax and parser
    * concrete syntax, core syntax, core semantics and reification
        * function type
        * inductive(nominal)/structural record/sum type
        * mutually recursive definitions
    * bidirectional elaborating type checker
    * evaluation/HOAS by generating JVM bytecode dynamically
    * conversion check and whnf with eta and recursive references
* `DONE` overlapping and order independent patterns, see `plus_tests` in library for detail
* `DONE` locally scoped meta; very simple unification; implicit arguments syntax
* `DONE` cubical features
    * path type
    * composition structure (hcomp, transp)
    * glue type and univalence, fibrant universe
    * sum type's composition structure, higher inductive types
* `DONE` cumulative universe with "lift" operator for global definitions (see [here](https://mazzo.li/epilogue/index.html%3Fp=857&cpage=1.html)) and universe/function subtyping
* SOUNDNESS *to reject codes, not actually providing new features!*
    * complete core checker (currently it is only a partial implementation)
    * positivity checker
    * coverage & confluence checker for overlapping patterns and for hits
    * termination checking: currently you don't need modifier `inductively` to write a recursive type, with termination checking, you should not be able to do this
* CORE THEORY EXTENSIONS
    * `RESEARCH` how we can incorporate XTT or/and two level system, or Arend style, or even both
        * why? we want more and more definitional equality
    * `RESEARCH` think how we can have a theory/syntax for partial elements and dimension
    * `RESEARCH` **efficient computation for Brunerie's number** (next thing to try: alternative full reduction mode)
    * **inductive families of two flavor**
    * more recursive types
        * inductive-inductive
        * inductive-recursive
        * is [this](https://arend.readthedocs.io/en/latest/language-reference/definitions/hits/#conditions) sound?
        * coinductive types?
    * native nat and int, or even native `int32` etc.
* MORE ELABORATION
    * **a typeclass like mechanism: maybe record calculus + subtyping + singleton types (one problem is dependency graph introduces syntax stuff in equality) + enriched type (a type with additional etype)**
    * `match` expressions: `a match ...`
    * implicit projection: gorup has inverse defined as a record of element with properties, `g.inverse`, `g.inverse::left`, `g.inverse::`
    * default parameter value (this requires `EType` to be compile instead of tunneled, also it will need to be nominal etc.? we can require them to be closed term... but???)
    * constant projection: `square.constant`. like static fields in Java
    * **projection: `1.is_even`. like extension functions in Dotty/Kotlin**
    * user defined patterns
    * implicit on the right: `a: #A`
        * user defined implicit right form
    * `g: G` typing notation with context-association
    * implicit conversions
* **STRUCTURAL EDITOR**
    * editor diractives and name sortcuts
* TACTIC
* USABILITY
    * HTML pretty print with inferred types, cross links, elaborated information, cross-linked core term
    * error reporting
        * disallow or warn naming shadowing
        * better error reporting
    * modules and compile unit (when it got slow, currently not worth the trouble)
    * compilation
* TESTING
    * translate to Agda to do correctness checking
* MATH
    * quick sort and properties
    * symmetry book
    * cubical Agda
    * Agda stdlib
    * Artin's or Lang's *Algebra*
    * Agda's test cases and it's issues
    * https://ncatlab.org/homotopytypetheory/show/open+problems#higher_algebra_and_higher_category_theory
        * seems interesting: limits problem?
    * https://github.com/HoTT/HoTT
    * unimath
    * the big problems list

*as programming language*

* COMPILATION
