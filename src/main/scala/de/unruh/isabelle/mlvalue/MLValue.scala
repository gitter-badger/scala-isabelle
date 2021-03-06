package de.unruh.isabelle.mlvalue

import de.unruh.isabelle.control.Isabelle.{DInt, DList, DObject, DString, Data, ID}
import de.unruh.isabelle.control.{Isabelle, IsabelleException, OperationCollection}
import de.unruh.isabelle.mlvalue.Implicits._
import MLValue.{Converter, Ops, logger}
import org.log4s
import scalaz.Id.Id

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/** A type safe wrapper for values stored in the Isabelle process managed by [[control.Isabelle Isabelle]].
  *
  * As explained in the documentation of [[control.Isabelle Isabelle]], the Isabelle process has an object store of values,
  * and the class [[control.Isabelle.ID Isabelle.ID]] is a reference to an object in that object store. However, values of different
  * types share the same object store. Thus [[control.Isabelle.ID Isabelle.ID]] is not type safe: there are compile time guarantees
  * that the value referenced by that ID has a specific type.
  *
  * [[MLValue]][A] is a thin wrapper around an ID [[id]] (more precisely, a future holding an ID). It is guaranteed
  * that [[id]] references a value in the Isabelle process of an ML type corresponding to `A` (or throw an exception).
  * (It is possible to violate this guarantee by type-casting the [[MLValue]] though.) For supported types `A`,
  * it is possible to automatically translate a Scala value `x` of type `A` into an `MLValue[A]` (which behind the scenes
  * means that `x` is transferred to the Isabelle process and added to the object store) and also to automatically
  * translate an `MLValue[A]` back to a Scala value of type `A`. Supported types are [[scala.Int Int]], [[scala.Long Long]],
  * [[scala.Boolean Boolean]], [[scala.Unit Unit]], [[java.lang.String String]], and lists and tuples (max. 7 elements)
  * of supported types. (It is also possible for `A` to be the type `MLValue[...]`, see [[MLValueConverter]] for
  * explanations (TODO add these explanations).) It is possible to
  * add support for other types, see [[MLValue.Converter]] for instructions. Using this
  * mechanism, support for the terms, types, theories, contexts, and theorems has been added in package
  * [[de.unruh.isabelle.pure]].
  *
  * In more detail:
  *  - For any supported Scala type `A` there should be a unique corresponding ML type `a`.
  *    (This is not enforced if we add support for new types, but if we violate this, we loose type safety.)
  *  - Several Scala types `A` are allowed to correspond to the same ML type `a`. (E.g., both [[scala.Long Long]] and [[scala.Int Int]]
  *    correspond to the unbounded `int` in ML.)
  *  - For each supported type `A`, the following must be specified (via an implicit [[MLValue.Converter]]):
  *    - an encoding of `a` as an exception (to be able to store it in the object store)
  *    - ML functions to translate between `a` and exceptions and back
  *    - retrieve function: how to retrieve an exception encoding an ML value of type `a` and translate it into an `A` in Scala
  *    - store function: how to translate an `A` in Scala into an an exception encoding an ML value of type `a` and store it in the
  *      object store.
  *  - `[[MLValue.apply MLValue]](x)` automatically translates `x:A` into a value of type `a` (using the retrieve function) and returns
  *    an `MLValue[A]`.
  *  - If `m : MLValue[A]`, then `m.`[[retrieve]] (asynchronous) and `m.`[[retrieveNow]] (synchronous) decode the ML
  *    value in the object store and return a Scala value of type `A`.
  *  - ML code that operates on values in the Isabelle process can be specified using [[MLValue.compileValue]]
  *    and [[MLValue.compileFunction[D,R]*]]. This ML code directly operates on the corresponding ML type `a` and
  *    does not need to consider the encoding of ML values as exceptions or how ML types are serialized to be transferred
  *    to Scala (all this is handled automatically behind the scenes using the information provided by the implicit
  *    [[MLValue.Converter]]).
  *  - To be able to use the automatic conversions etc., converters need to be imported for supported types.
  *    The converters provided by this package can be imported by `import [[de.unruh.isabelle.mlvalue.Implicits]]._`.
  *
  * Note: Some operations take an [[control.Isabelle Isabelle]] instance as an implicit argument. It is required that this instance
  *       the same as the one relative to which the MLValue was created.
  *
  * A full example:
  * {{{
  *     implicit val isabelle: Isabelle = new Isabelle(...)
  *     import scala.concurrent.ExecutionContext.Implicits._
  *
  *     // Create an MLValue containing an integer
  *     val intML : MLValue[Int] = MLValue(123)
  *     // 123 is now stored in the object store
  *
  *     // Fetching the integer back
  *     val int : Int = intML.retrieveNow
  *     assert(int == 123)
  *
  *     // The type parameter of MLValue ensures that the following does not compile:
  *     // val string : String = intML.retrieveNow
  *
  *     // We write an ML function that squares an integer and converts it into a string
  *     val mlFunction : MLFunction[Int, String] =
  *       MLValue.compileFunction[Int, String]("fn i => string_of_int (i*i)")
  *
  *     // We can apply the function to an integer stored in the Isabelle process
  *     val result : MLValue[String] = mlFunction(intML)
  *     // The result is still stored in the Isabelle process, but we can retrieve it:
  *     val resultHere : String = result.retrieveNow
  *     assert(resultHere == "15129")
  * }}}
  * Not that the type annotations in this example are all optional, the compiler infers them automatically.
  * We have included them for clarity only.
  *
  * @param id the ID of the referenced object in the Isabelle process
  * @tparam A the Scala type corresponding to the ML type of the value referenced by [[id]]
  */
class MLValue[A] protected (/** the ID of the referenced object in the Isabelle process */ val id: Future[Isabelle.ID])
  extends FutureValue {
  def logError(message: => String)(implicit executionContext: ExecutionContext): this.type = {
    id.onComplete {
      case Success(_) =>
      case Failure(exception) => logger.error(exception)(message)
    }
    this
  }

  /** Returns a textual representation of the value in the ML process as it is stored in the object store
   * (i.e., encoded as an exception). E.g., an integer 3 would be represented as "E_Int 3". */
  def debugInfo(implicit isabelle: Isabelle, ec: ExecutionContext): String =
    Ops.debugInfo[A](this).retrieveNow

  override def await: Unit = Await.result(id, Duration.Inf)
  override def someFuture: Future[Any] = id

  /** Retrieves the value referenced by this MLValue from the Isabelle process.
   *
   * In particular, the value in the Isabelle process (a value in ML) is translated to a Scala value.
   *
   * @return Future holding the value (as a Scala value) or an [[control.IsabelleException IsabelleException]] if the computation of that
   *         value or the transfer to Scala failed.
   * @param converter This converter specifies how the value is to be retrieved from the Isabelle process and
   *                  translated into a Scala value of type `A`
   * @param isabelle The [[control.Isabelle Isabelle]] instance holding the value. This must be the same `Isabelle` instance
   *                 relative to which the `MLValue` was created. (Otherwise unspecified data is returned or an
   *                 exception thrown.) In an application with only a single `Isabelle` instance that instance
   *                 can safely be declared as an implicit.
   */
  @inline def retrieve(implicit converter: Converter[A], isabelle: Isabelle, ec: ExecutionContext): Future[A] =
    converter.retrieve(this)

  /** Like retrieve but returns the Scala value directly instread of a future (blocks till the computation
   * and transfer finish. */
  @inline def retrieveNow(implicit converter: Converter[A], isabelle: Isabelle, ec: ExecutionContext): A =
    Await.result(retrieve, Duration.Inf)

  /** Returns this MLValue as an [[MLFunction]], assuming this MLValue has a type of the form `MLValue[D => R]`.
   * If this MLValue is `MLValue[D => R]`, it means it references a function value in the ML process. Converting it
   * to an `MLFunction <: MLValue` gives us access to additional methods for applying this function.
   * @see [[MLFunction]]
   */
  def function[D, R](implicit ev: MLValue[A] =:= MLValue[D => R]): MLFunction[D, R] =
    MLFunction.unsafeFromId(id)

  /** Analogous to [[function]] but for functions that take a pair as argument, i.e., `this : MLValue[((D1, D2)) => R]`.
   * @see [[MLFunction2]] */
  def function2[D1, D2, R](implicit ev: MLValue[A] =:= MLValue[((D1, D2)) => R]): MLFunction2[D1, D2, R] =
    MLFunction2.unsafeFromId(id)

  /** Analogous to [[function]] but for functions that take a 3-tuple as argument, i.e., `this : MLValue[((D1, D2, D3)) => R]`.
   * @see [[MLFunction3]] */
  def function3[D1, D2, D3, R](implicit ev: MLValue[A] =:= MLValue[((D1, D2, D3)) => R]): MLFunction3[D1, D2, D3, R] =
    MLFunction3.unsafeFromId(id)

  /** Analogous to [[function]] but for functions that take a 4-tuple as argument, i.e., `this : MLValue[((D1, D2, D3, D4)) => R]`.
   * @see [[MLFunction4]] */
  def function4[D1, D2, D3, D4, R](implicit ev: MLValue[A] =:= MLValue[((D1, D2, D3, D4)) => R]): MLFunction4[D1, D2, D3, D4, R] =
    MLFunction4.unsafeFromId(id)

  /** Analogous to [[function]] but for functions that take a 5-tuple as argument, i.e., `this : MLValue[((D1, D2, D3, D4, D5)) => R]`.
   * @see [[MLFunction5]] */
  def function5[D1, D2, D3, D4, D5, R](implicit ev: MLValue[A] =:= MLValue[((D1, D2, D3, D4, D5)) => R]): MLFunction5[D1, D2, D3, D4, D5, R] =
    MLFunction5.unsafeFromId(id)

  /** Analogous to [[function]] but for functions that take a 6-tuple as argument, i.e., `this : MLValue[((D1, D2, D3, D4, D5, D6)) => R]`.
   * @see [[MLFunction6]] */
  def function6[D1, D2, D3, D4, D5, D6, R](implicit ev: MLValue[A] =:= MLValue[((D1, D2, D3, D4, D5, D6)) => R]): MLFunction6[D1, D2, D3, D4, D5, D6, R] =
    MLFunction6.unsafeFromId(id)

  /** Analogous to [[function]] but for functions that take a 7-tuple as argument, i.e., `this : MLValue[((D1, D2, D3, D4, D5, D6, D7)) => R]`.
   * @see [[MLFunction7]] */
  def function7[D1, D2, D3, D4, D5, D6, D7, R](implicit ev: MLValue[A] =:= MLValue[((D1, D2, D3, D4, D5, D6, D7)) => R]): MLFunction7[D1, D2, D3, D4, D5, D6, D7, R] =
    MLFunction7.unsafeFromId(id)

  /** Specialized type cast that inserts `MLValue[]` in arbitrary positions in the type parameter of this MLValue.
   * E.g., we can type cast `this : MLValue[List[X]]` to `MLValue[List[MLValue[X]]]` by invoking `this.insertMLValue[List,X]`
   * Such type casts are safe because the the way `MLValue[...]` is interpreted in the type parameter to `MLValue` (see
   * [[MLValueConverter]] (TODO: document that one)). The same type cast could be achieved using `.asInstanceOf`, but
   * `insertMLValue` guarantees that no unsafe cast is accidentally performed.
   */
  @inline def insertMLValue[C[_],B](implicit ev: A =:= C[B]): MLValue[C[MLValue[B]]] = this.asInstanceOf[MLValue[C[MLValue[B]]]]
  /** Specialized type cast that removes `MLValue[]` in arbitrary positions in the type parameter of this MLValue.
   * E.g., we can type cast `this : MLValue[List[MLValue[X]]]` to `MLValue[List[X]]` by invoking `this.removeMLValue[List,X]`
   * Such type casts are safe because the the way `MLValue[...]` is interpreted in the type parameter to `MLValue` (see
   * [[MLValueConverter]] (TODO: document that one)). The same type cast could be achieved using `.asInstanceOf`, but
   * `insertMLValue` guarantees that no unsafe cast is accidentally performed.
   */
  @inline def removeMLValue[C[_],B](implicit ev: A =:= C[MLValue[B]]): MLValue[C[B]] = this.asInstanceOf[MLValue[C[B]]]
}



// TODO: Document API
class MLStoreFunction[A] private (val id: Future[ID]) {
  def apply(data: Data)(implicit isabelle: Isabelle, ec: ExecutionContext, converter: Converter[A]): MLValue[A] = {
    // Maybe inherit from MLFunction instead?
    MLValue.unsafeFromId(isabelle.applyFunction(this.id, data).map {
      case DObject(id) => id
      case _ => throw IsabelleException("MLStoreFunction")
    })
  }

  def apply(data: Future[Data])(implicit isabelle: Isabelle, ec: ExecutionContext, converter: Converter[A]): MLValue[A] =
    MLValue.unsafeFromId(for (data <- data; DObject(id) <- isabelle.applyFunction(this.id, data)) yield id)
}

// TODO: Document API
object MLStoreFunction {
  def apply[A](ml: String)(implicit isabelle: Isabelle, converter: Converter[A]) : MLStoreFunction[A] =
    new MLStoreFunction(isabelle.storeValue(s"E_Function (DObject o (${converter.valueToExn}) o ($ml))"))
}

// TODO: Document API
// Maybe inherit from MLFunction?
class MLRetrieveFunction[A] private (val id: Future[ID]) {
  def apply(id: ID)(implicit isabelle: Isabelle, ec: ExecutionContext): Future[Isabelle.Data] =
    isabelle.applyFunction(this.id, DObject(id))
  def apply(id: Future[ID])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[Isabelle.Data] =
    for (id <- id; data <- apply(id)) yield data
  def apply(value: MLValue[A])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[Data] =
    apply(value.id)
}

// TODO: Document API
object MLRetrieveFunction {
  def apply[A](ml: String)(implicit isabelle: Isabelle, converter: Converter[A]) : MLRetrieveFunction[A] =
    new MLRetrieveFunction(isabelle.storeValue(s"E_Function (fn DObject x => ($ml) ((${converter.exnToValue}) x))"))
}

object MLValue extends OperationCollection {
  /** Unsafe operation for creating an [[MLValue]].
   * It is the callers responsibility to ensure that `id` refers to an value of the right type in the object store.
   * Using this function should rarely be necessary, except possibly when defining new [[Converter]]s.
   */
  def unsafeFromId[A](id: Future[Isabelle.ID]) = new MLValue[A](id)
  def unsafeFromId[A](id: Isabelle.ID): MLValue[A] = unsafeFromId[A](Future.successful(id))

  /** Utility method for generating ML code.
   * It returns an ML fragment that can be used as the fallback case when pattern matching exceptions,
   * the fragment raises an error with a description of the exception.
   *
   * Example:
   * Instead of ML code `"fn E_Int i => i"`, we can write `s"fn E_Int i => i | \${matchFailExn("my function")}"`
   * to get more informative error messages on pattern match failures.
   *
   * @param name A short description of the purpose of the match/ML function that is being written.
   *             Will be included in the error message.
   */
  @inline def matchFailExn(name: String) =
    s""" exn => error ("Match failed in ML code generated for $name: " ^ string_of_exn exn)"""

  /** Utlity method for generating ML code. Analogous to [[matchFailExn]], but for cases when we
   * pattern match a value of type `data`. */
  @inline def matchFailData(name: String) =
    s""" data => error ("Match failed in ML code generated for $name: " ^ string_of_data data)"""

  private val logger = log4s.getLogger

  override protected def newOps(implicit isabelle: Isabelle, ec: ExecutionContext) : Ops = new Ops()

  //noinspection TypeAnnotation
  protected[mlvalue] class Ops(implicit val isabelle: Isabelle, ec: ExecutionContext) {
    isabelle.executeMLCodeNow(
      """exception E_List of exn list
         exception E_Bool of bool
         exception E_Option of exn option
         exception E_Int of int
         exception E_String of string
         exception E_Pair of exn * exn""")

    val unitValue = MLValue.compileValueRaw[Unit]("E_Int 0")

    val retrieveTuple2 =
      MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing])]("fn (a,b) => DList [DObject a, DObject b]")
    /*@inline def retrieveTuple2[A,B]: MLRetrieveFunction[(MLValue[A], MLValue[B])] =
      retrieveTuple2_.asInstanceOf[MLRetrieveFunction[(MLValue[A], MLValue[B])]]*/
    private val storeTuple2_ =
      MLStoreFunction[(MLValue[Nothing], MLValue[Nothing])](s"fn DList [DObject a, DObject b] => (a,b) | ${matchFailData("storeTuple2")}")
    @inline def storeTuple2[A,B]: MLStoreFunction[(MLValue[A], MLValue[B])] =
      storeTuple2_.asInstanceOf[MLStoreFunction[(MLValue[A], MLValue[B])]]

    val retrieveTuple3: MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])] =
      MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])](s"fn (a,b,c) => DList [DObject a, DObject b, DObject c]")
    private val storeTuple3_ =
      MLStoreFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])](s"fn DList [DObject a, DObject b, DObject c] => (a,b,c) | ${matchFailData("storeTuple3")}")
    @inline def storeTuple3[A,B,C]: MLStoreFunction[(MLValue[A], MLValue[B], MLValue[C])] =
      storeTuple3_.asInstanceOf[MLStoreFunction[(MLValue[A], MLValue[B], MLValue[C])]]

    val retrieveTuple4: MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])] =
      MLRetrieveFunction(s"fn (a,b,c,d) => DList [DObject a, DObject b, DObject c, DObject d]")
    private val storeTuple4_ =
      MLStoreFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])](s"fn DList [DObject a, DObject b, DObject c, DObject d] => (a,b,c,d) | ${matchFailData("storeTuple4")}")
    @inline def storeTuple4[A,B,C,D] =
      storeTuple4_.asInstanceOf[MLStoreFunction[(MLValue[A], MLValue[B], MLValue[C], MLValue[D])]]

    val retrieveTuple5: MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])] =
      MLRetrieveFunction(s"fn (a,b,c,d,e) => DList [DObject a, DObject b, DObject c, DObject d, DObject e]")
    private val storeTuple5_ =
      MLStoreFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])](
        s"fn DList [DObject a, DObject b, DObject c, DObject d, DObject e] => (a,b,c,d,e) | ${matchFailData("storeTuple5")}")
    @inline def storeTuple5[A,B,C,D,E] =
      storeTuple5_.asInstanceOf[MLStoreFunction[(MLValue[A], MLValue[B], MLValue[C], MLValue[D], MLValue[E])]]

    val retrieveTuple6: MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])] =
      MLRetrieveFunction(s"fn (a,b,c,d,e,f) => DList [DObject a, DObject b, DObject c, DObject d, DObject e, DObject f]")
    private val storeTuple6_ =
      MLStoreFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])](
        s"fn DList [DObject a, DObject b, DObject c, DObject d, DObject e, DObject f] => (a,b,c,d,e,f) | ${matchFailData("storeTuple6")}")
    @inline def storeTuple6[A,B,C,D,E,F] =
      storeTuple6_.asInstanceOf[MLStoreFunction[(MLValue[A], MLValue[B], MLValue[C], MLValue[D], MLValue[E], MLValue[F])]]

    val retrieveTuple7: MLRetrieveFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])] =
      MLRetrieveFunction(s"fn (a,b,c,d,e,f,g) => DList [DObject a, DObject b, DObject c, DObject d, DObject e, DObject f, DObject g]")
    private val storeTuple7_ =
      MLStoreFunction[(MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing], MLValue[Nothing])](
        s"fn DList [DObject a, DObject b, DObject c, DObject d, DObject e, DObject f, DObject g] => (a,b,c,d,e,f,g) | ${matchFailData("storeTuple7")}")
    @inline def storeTuple7[A,B,C,D,E,F,G] =
      storeTuple7_.asInstanceOf[MLStoreFunction[(MLValue[A], MLValue[B], MLValue[C], MLValue[D], MLValue[E], MLValue[F], MLValue[G])]]

    val retrieveInt = MLRetrieveFunction[Int]("DInt")
    val storeInt = MLStoreFunction[Int]("fn DInt i => i")
    val retrieveLong = MLRetrieveFunction[Long]("DInt")
    val storeLong = MLStoreFunction[Long]("fn DInt i => i")

    val retrieveString: MLRetrieveFunction[String] = MLRetrieveFunction[String]("DString")
    val storeString: MLStoreFunction[String] = MLStoreFunction[String]("fn DString str => str")

//    val boolToInt : MLFunction[Boolean, Int] = MLValue.compileFunction[Boolean, Int]("fn true => 1 | false => 0")
    val boolTrue : MLValue[Boolean] = MLValue.compileValue("true")
    val boolFalse : MLValue[Boolean] = MLValue.compileValue("false")
    val retrieveBool : MLRetrieveFunction[Boolean] =
      MLRetrieveFunction("fn true => DInt 1 | false => DInt 0")

    private val optionNone_ = MLValue.compileValueRaw[Option[_]]("E_Option NONE")
    def optionNone[A]: MLValue[Option[A]] = optionNone_.asInstanceOf[MLValue[Option[A]]]
    private val optionSome_ = MLValue.compileFunctionRaw[Nothing, Option[Nothing]]("E_Option o SOME")
    def optionSome[A]: MLFunction[A, Option[A]] = optionSome_.asInstanceOf[MLFunction[A, Option[A]]]
    val retrieveOption : MLRetrieveFunction[Option[MLValue[Nothing]]] =
      MLRetrieveFunction("fn NONE => DList [] | SOME x => DList [DObject x]")


    val retrieveList : MLRetrieveFunction[List[MLValue[Nothing]]] =
      MLRetrieveFunction("DList o map DObject")
    val storeList : MLStoreFunction[List[MLValue[Nothing]]] =
      MLStoreFunction(s"fn DList list => map (fn DObject obj => obj | ${matchFailData("storeList.map")}) list | ${matchFailData("storeList")}")

    val debugInfo_ : MLFunction[MLValue[Nothing], String] =
      compileFunctionRaw[MLValue[Nothing], String]("E_String o string_of_exn")
    def debugInfo[A]: MLFunction[MLValue[A], String] = debugInfo_.asInstanceOf[MLFunction[MLValue[A], String]]
  }

  /** An instance of this class describes the relationship between a Scala type `A`, the corresponding ML type `a`,
    * and the representation of values of type `a` as exceptions in the object store. To support new types,
    * a corresponding [[Converter]] object/class needs to be declared.
    *
    * We explain how a converter works using the example of [[IntConverter]].
    *
    * The first step is to decide which
    * Scala type and which ML type should be related. In this case, we choose [[scala.Int]] on the Scala side,
    * and `int` on the ML side.
    * We declare the correspondence using the [[mlType]] method:
    * {{{
    *   final object IntConverter extends MLValue.Converter[A] {
    *     override def mlType = "int"
    *     ...
    *   }
    * }}}
    *
    * Next, we have to decide how decide how code is converted from an `int` to an exception (so that it can be stored
    * in the object store). In this simple case, we first declare a new exception for holding integers:
    * {{{
    *   isabelle.executeMLCodeNow("exception E_Int of int")
    * }}}
    * This should be done globally (once per Isabelle instance). Declaring two (even identical) exceptions with the
    * same name `E_Int` must be avoided! See [[control.OperationCollection OperationCollection]] for utilities how to manage this. (`E_Int`
    * specifically is declared in [[MLValue]] when calling [[MLValue.init]].)
    *
    * We define the method [[valueToExn]] that returns the ML source code to convert an ML value of type `int` to an exception:
    * {{{
    * final object IntConverter extends MLValue.Converter[A] {
    *     ...
    *     override def valueToExn: String = "fn x => E_Int x"  // or equivalently: = "E_Int"
    *     ...
    *   }
    * }}}
    *
    * We also need to convert in the opposite direction:
    * {{{
    * final object IntConverter extends MLValue.Converter[A] {
    *     ...
    *     override def exnToValue: String = "fn (E_Int x) => x"
    *     ...
    *   }
    * }}}
    * (Best add some meaningful exception in case of match failure, e.g., using boilerplate from [[MLValue.matchFailExn]].
    * Omitted for clarity in this example.)
    *
    * TODO: Document [[retrieve]], [[store]]
    *
    * TODO: Declare implicits
    *
    * Notes
    *  - Several Scala types can correspond to the same ML type (e.g., [[scala.Int Int]] and
    *    [[scala.Long Long]] both correspond to `int`).
    *  - If the converters for two Scala types `A`,`B` additionally have the same encoding as exceptions (defined via [[valueToExn]],
    *    [[exnToValue]] in their [[Converter]]s), then [[MLValue]][A] and [[MLValue]][B] can be safely typecast into
    *    each other.
    *  - TODO how about two ML types with the same Scala type?
    *
    * @tparam A the Scala type for which a corresponding ML type is declared
    */
  abstract class Converter[A] {
    /** Returns the ML type corresponding to [[A]].
     *
     * If it is not possible to determine this type (this can happen in rare situations, e.g.,
     * in [[MLValueConverter]]), a typ involving placeholders `_` can be used. In that case,
     * the most specific type possible should be used.
     *
     * This function should always return the same value. (It is declared as a `def` only to make sure
      * Scala does not include an extra field or perform an unnecessary computation in the class when this function
      * is not used. */
    def mlType : String
    // TODO: Document
    def retrieve(value: MLValue[A])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[A]
    // TODO: Document
    def store(value: A)(implicit isabelle: Isabelle, ec: ExecutionContext): MLValue[A]
    /** This function should always return the same value. (It is declared as a `def` only to make sure
     * Scala does not include an extra field or perform an unnecessary computation in the class when this function
     * is not used. */
    // TODO: Document
    def exnToValue : String
    /** This function should always return the same value. (It is declared as a `def` only to make sure
     * Scala does not include an extra field or perform an unnecessary computation in the class when this function
     * is not used. */
    // TODO: Document
    def valueToExn : String
  }

  // TODO: Document API
  @inline def apply[A](value: A)(implicit conv: Converter[A], isabelle: Isabelle, executionContext: ExecutionContext) : MLValue[A] =
    conv.store(value)

  // TODO: Document API
  def compileValueRaw[A](ml: String)(implicit isabelle: Isabelle, ec: ExecutionContext): MLValue[A] =
    new MLValue[A](isabelle.storeValue(ml)).logError(s"""Error while compiling value "$ml":""")

  // TODO: Document API
  def compileValue[A](ml: String)(implicit isabelle: Isabelle, ec: ExecutionContext, converter: Converter[A]): MLValue[A] =
    compileValueRaw[A](s"(${converter.valueToExn}) ($ml)")

  // TODO: Document API
  def compileFunctionRaw[D, R](ml: String)(implicit isabelle: Isabelle, ec: ExecutionContext): MLFunction[D, R] =
    MLFunction.unsafeFromId[D,R](isabelle.storeValue(s"E_Function (fn DObject x => ($ml) x |> DObject)")).logError(s"""Error while compiling function "$ml":""")

  // TODO: Document API
  def compileFunction[D, R](ml: String)(implicit isabelle: Isabelle, ec: ExecutionContext, converterA: Converter[D], converterB: Converter[R]): MLFunction[D, R] =
    compileFunctionRaw(s"(${converterB.valueToExn}) o ($ml) o (${converterA.exnToValue})")

  // TODO: Document API
  def compileFunction[D1, D2, R](ml: String)
                                 (implicit isabelle: Isabelle, ec: ExecutionContext,
                                 converter1: Converter[D1], converter2: Converter[D2], converterR: Converter[R]): MLFunction2[D1, D2, R] =
    compileFunction[(D1,D2), R](ml).function2

  // TODO: Document API
  def compileFunction[D1, D2, D3, R](ml: String)
                                    (implicit isabelle: Isabelle, ec: ExecutionContext,
                                     converter1: Converter[D1], converter2: Converter[D2], converter3: Converter[D3],
                                     converterR: Converter[R]): MLFunction3[D1, D2, D3, R] =
    compileFunction[(D1,D2,D3), R](ml).function3

  // TODO: Document API
  def compileFunction[D1, D2, D3, D4, R](ml: String)
                                    (implicit isabelle: Isabelle, ec: ExecutionContext,
                                     converter1: Converter[D1], converter2: Converter[D2], converter3: Converter[D3],
                                     converter4: Converter[D4], converterR: Converter[R]): MLFunction4[D1, D2, D3, D4, R] =
    compileFunction[(D1,D2,D3,D4), R](ml).function4

  // TODO: Document API
  def compileFunction[D1, D2, D3, D4, D5, R](ml: String)
                                        (implicit isabelle: Isabelle, ec: ExecutionContext,
                                         converter1: Converter[D1], converter2: Converter[D2], converter3: Converter[D3],
                                         converter4: Converter[D4], converter5: Converter[D5], converterR: Converter[R]): MLFunction5[D1, D2, D3, D4, D5, R] =
    compileFunction[(D1,D2,D3,D4,D5), R](ml).function5

  // TODO: Document API
  def compileFunction[D1, D2, D3, D4, D5, D6, R](ml: String)
                                                (implicit isabelle: Isabelle, ec: ExecutionContext,
                                                 converter1: Converter[D1], converter2: Converter[D2], converter3: Converter[D3],
                                                 converter4: Converter[D4], converter5: Converter[D5], converter6: Converter[D6], converterR: Converter[R]): MLFunction6[D1, D2, D3, D4, D5, D6, R] =
    compileFunction[(D1,D2,D3,D4,D5,D6), R](ml).function6

  // TODO: Document API
  def compileFunction[D1, D2, D3, D4, D5, D6, D7, R](ml: String)
                                                    (implicit isabelle: Isabelle, ec: ExecutionContext,
                                                     converter1: Converter[D1], converter2: Converter[D2], converter3: Converter[D3],
                                                     converter4: Converter[D4], converter5: Converter[D5], converter6: Converter[D6],
                                                     converter7: Converter[D7], converterR: Converter[R]): MLFunction7[D1, D2, D3, D4, D5, D6, D7, R] =
    compileFunction[(D1,D2,D3,D4,D5,D6,D7), R](ml).function7
}