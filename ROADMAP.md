# Roadmap

* **DONE** totally unsafe MLTT basics
    * basic `.poor` syntax and parser
    * concrete syntax, core syntax, core semantics and reification
        * function type
        * inductive(nominal)/structural record/sum type
        * mutually recursive definitions
    * bidirectional elaborating type checker
    * conversion check and whnf with eta and recursive references
* **DONE** overlapping and order independent patterns, see `plus_tests` in library for detail
* **DONE** locally scoped meta; very simple unification; implicit arguments syntax
    * *problem: the meta solution is not type checked, and other cases metas is not solved see FIXMEs in code*
* **DONE** cubical features
    * path type
    * composition structure (hcomp, transp)
    * glue type and univalence, fibrant universe
    * sum type's composition structure, higher inductive types
* SOUNDNESS *to reject codes, not actually providing new features!*
    * core checker, so we check meta solutions. also as a double check of elaboration
    * positivity checker
    * coverage & confluence checker for overlapping patterns and for hits
    * termination checking: currently you don't need modifier `inductively` to write a recursive type, with termination checking, you should not be able to do this
* CORE THEORY EXTENSIONS
    * **RESEARCH** cumulative universe with "lift" operator for global definitions (see [here](https://mazzo.li/epilogue/index.html%3Fp=857&cpage=1.html)) and subtyping
        * how we can incorporate XTT or/and two level system, or Arend style, or even both
    * **RESEARCH** think how we can have a theory/syntax for partial elements and dimension
    * **RESEARCH** efficient computation for Brunerie's number
    * more recursive types
        * inductive families of two flavor
        * inductive-inductive
        * inductive-recursive
        * is [this](https://arend.readthedocs.io/en/latest/language-reference/definitions/hits/#conditions) sound?
        * coinductive types?
* MORE ELABORATION
    * record calculus (one problem is dependency graph introduces syntax stuff in equality)
    * `match` expressions
    * **RESEARCH** calculus of elaboration
        * refactor implicit arguments
        * implicit projection *for example group has inverse defined as a record of element with properties, `g.inverse`, `g.inverse::left`, `g.inverse::`*
        * default parameter value
        * constant projection `square.constant`
        * projection `1.is_even`
        * user defined patterns (this might be simple!)
        * user defined implicit right form
        * `g: G` typing notation with context-association
    * implicit conversions
* USABILITY
    * HTML pretty print with inferred types, cross links, elaborated information, cross-linked core term
    * error reporting
        * disallow or warn naming shadowing
        * better error reporting
    * modules and compile unit
    * structural editor
        * editor diractives and name sortcuts
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