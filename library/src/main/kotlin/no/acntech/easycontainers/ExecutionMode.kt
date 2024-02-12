package no.acntech.easycontainers

/**
 * Enum class representing the execution mode of the container

 */
enum class ExecutionMode {
   /**
    * Service mode - continuous execution
    */
   SERVICE,

   /**
    * Task mode - single execution with an exit value
    */
   TASK
}