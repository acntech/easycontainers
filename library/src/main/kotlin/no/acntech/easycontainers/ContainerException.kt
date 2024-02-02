package no.acntech.easycontainers

open class ContainerException : RuntimeException {

   constructor() : super()

   constructor(cause: Throwable) : super(cause)

   constructor(message: String) : super(message)

   constructor(message: String, cause: Throwable) : super(message, cause)


}