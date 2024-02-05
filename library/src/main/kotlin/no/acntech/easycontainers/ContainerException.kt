package no.acntech.easycontainers

/**
 * ContainerException is a subclass of RuntimeException that is used to represent exceptions
 * that occur in the context of container operations.
 */
open class ContainerException : RuntimeException {

   /**
    * Creates an instance of the class with no arguments. It calls the superclass constructor.
    */
   constructor() : super()


   constructor(cause: Throwable) : super(cause)


   constructor(message: String) : super(message)

   /**
    * Constructs a new instance of the {@code ContainerException} class with the specified message and cause.
    *
    * @param message The detail message of the exception.
    * @param cause The cause of the exception.
    */
   constructor(message: String, cause: Throwable) : super(message, cause)

}