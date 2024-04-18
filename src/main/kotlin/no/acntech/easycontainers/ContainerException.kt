package no.acntech.easycontainers

/**
 * ContainerException is a subclass of RuntimeException that is used to represent exceptions
 * that occur in the context of container operations.
 */
open class ContainerException : RuntimeException {

   constructor() : super()

   constructor(cause: Throwable) : super(cause)

   constructor(message: String) : super(message)

   constructor(message: String, cause: Throwable) : super(message, cause)

}