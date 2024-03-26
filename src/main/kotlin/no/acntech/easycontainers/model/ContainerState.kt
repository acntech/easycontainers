package no.acntech.easycontainers.model

enum class ContainerState {

   /**
    * The container is in an uninitiated state.
    */
   UNINITIATED,

   /**
    * The container is being initialized.
    */
   INITIALIZING,

   /**
    * The container is running.
    */
   RUNNING,

   /**
    * The container has failed.
    */
   FAILED,

   /**
    * The container is being terminated.
    */
   TERMINATING,

   /**
    * The container is in an unknown state.
    */
   UNKNOWN,

   /**
    * The container has been gracefully or forcefully stopped.
    */
   STOPPED,

   /**
    * The container has been removed from the underlying container platform.
    */
   DELETED
}