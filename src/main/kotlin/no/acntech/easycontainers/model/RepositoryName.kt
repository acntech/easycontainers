package no.acntech.easycontainers.model

import no.acntech.easycontainers.model.base.SimpleValueObject
import no.acntech.easycontainers.util.text.RegexValidator
import no.acntech.easycontainers.util.text.StringValidator

/**
 * Represents a repository name in a software repository system.
 *
 * A repository name is a value object that holds a single value of type String. It is used to identify
 * a specific repository within a registry. The repository name must adhere to certain rules:
 * - It must consist of lowercase alphanumeric characters, and may contain hyphens, underscores, and periods.
 * - It must have a minimum length of 2 and a maximum length of 255 characters.
 *
 * Example Usage:
 *
 * ```
 * val repoName = RepositoryName.of("myrepo")
 * val defaultName = RepositoryName.DEFAULT
 * ```
 */
@JvmInline
value class RepositoryName(val value: String) : SimpleValueObject<String> {

   companion object {

      // REGEXP: Defines a regular expression pattern for validating repository names.
      // A valid repository name must start with a lowercase alphanumeric character (a-z, 0-9).
      // It can include dashes (-), underscores (_) or periods (.) but these characters must be followed by at least one
      // alphanumeric character (a-z, 0-9). The pattern prevents repository names from ending with special characters like a
      // dash (-), underscore (_) or period (.).
      private val REGEXP: Regex = "^[a-z0-9]+([-_.][a-z0-9]+)*\$".toRegex()

      private val VALIDATOR = StringValidator(
         minLength = 2,
         maxLength = 255,
         lexicalValidator = RegexValidator(REGEXP)
      )

      val DEFAULT = RepositoryName("default")

      val TEST = RepositoryName("test")

      fun of(value: String): RepositoryName {
         return RepositoryName(value)
      }
   }

   init {
      VALIDATOR.validate(this.unwrap())
   }

   override fun unwrap(): String {
      return value
   }

   override fun toString(): String {
      return value
   }

}
