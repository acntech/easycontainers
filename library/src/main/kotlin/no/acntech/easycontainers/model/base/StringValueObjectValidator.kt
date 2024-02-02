package no.acntech.easycontainers.model.base

import no.acntech.easycontainers.util.lang.ValidationException
import no.acntech.easycontainers.util.lang.Validator

class StringValueObjectValidator(
   range: ValidationRange<String> = ValidationRange(),
   private val minLength: Int? = null,
   private val maxLength: Int? = null,
   private val lexicalValidator: Validator<String>? = null,
   private val syntaxValidator: Validator<String>? = null,
   private val semanticsValidator: Validator<String>? = null,
) : SimpleValueObjectValidator<String>(range) {

   override fun validate(t: SimpleValueObject<String>) {
      super.validate(t) // Call base class validation

      val value = t.unwrap()

      // Length checks
      minLength?.let { min ->
         if (value.length < min) throw ValidationException("String length '${value.length}' below allowed minimum '$min'")
      }

      maxLength?.let { max ->
         if (value.length > max) throw ValidationException("String length '${value.length}' exceeds allowed maximum '$max'")
      }

      // Lexical content checks
      lexicalValidator?.validate(value)

      // Syntax checks
      syntaxValidator?.validate(value)

      // Semantics checks
      semanticsValidator?.validate(value)
   }
}
