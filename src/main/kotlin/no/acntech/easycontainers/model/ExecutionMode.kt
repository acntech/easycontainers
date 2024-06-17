package no.acntech.easycontainers.model

/**
 * Enum class representing the execution mode of the container.
 * @see [ContainerX]
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