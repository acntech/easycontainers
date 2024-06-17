package no.acntech.easycontainers

/**
 * PermissionException is a subclass of ContainerException that is used to represent exceptions that occur when
 * there is a permission issue.
 */
class PermissionException : ContainerException {

   constructor() : super()

   constructor(message: String) : super(message)

   constructor(cause: Throwable) : super(cause)

   constructor(message: String, cause: Throwable) : super(message, cause)

}
