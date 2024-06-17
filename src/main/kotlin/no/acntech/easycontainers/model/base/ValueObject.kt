package no.acntech.easycontainers.model.base

/**
 * Marker interface for DDD Value Objects (domain primitives) - see e.g. https://en.wikipedia.org/wiki/Value_object.
 * A ValueObject <i>must</i> be final, pre-validated (before or during construction) and immutable.
 * <p>
 * Some articles with more in-depth explanation of value objects and their importance in software design:<br>
 * <ul>
 *  <li>https://freecontent.manning.com/domain-primitives-what-they-are-and-how-you-can-use-them-to-make-more-secure-software/</li>
 *  <li>https://medium.com/@edin.sahbaz/the-pitfalls-of-primitive-obsession-an-insight-into-code-quality-using-net-c-b1898bcffb4d</li>
 * </ul>
 */
interface ValueObject
