package no.acntech.easycontainers

class PermissionException : ContainerException {

   constructor(message: String) : super(message)

   constructor(message: String, cause: Throwable) : super(message, cause)

}
