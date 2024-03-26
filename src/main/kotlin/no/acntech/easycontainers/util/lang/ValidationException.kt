package no.acntech.easycontainers.util.lang

/**
 * Exception that is thrown when a validation error occurs.
 *
 * @constructor Creates a new ValidationException.
 * @param message The detail message.
 * @param cause The cause of the exception.
 */
class ValidationException : RuntimeException {

   constructor() : super()

   constructor(message: String) : super(message)

   constructor(message: String, cause: Throwable) : super(message, cause)

   constructor(cause: Throwable) : super(cause)
}
