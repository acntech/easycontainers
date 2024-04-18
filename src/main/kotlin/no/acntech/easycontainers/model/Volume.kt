package no.acntech.easycontainers.model

import java.nio.file.Path

/**
 * Value object representing a volume to be added or mapped into a container.
 * <ul>
 *  <li>For Docker, if the VolumeName exists as a Docker named volume, it will be used, and the hostDir will be ignored</li>
 *
 *  <li>For Kubernetes the VolumeName must <i>either<</i> refer to a corresponding PersistentVolume
 *  - see <a href="https://kubernetes.io/docs/concepts/storage/volumes/">PersistentVolume</a>, <i>or</i>
 *  memoryBacked must be true. In case of a PersistentVolume, the PersistentVolumeClaimName will be created and used irrespective
 *  of the existence of the PersistentVolume - and will hence fail if no PersistentVolume exist.</li>
 * </ul>
 *
 * @param name The name of the volume
 * @param mountDir The path in the container where the volume will be mounted
 * @param hostDir Only valid for Docker - if set, the volume will be mounted from the host
 * @param memoryBacked Valid for Docker and Kubernetes - if true, the volume will be memory backed using tmpfs for Docker and
 * emptyDir with medium 'Memory' for Kubernetes
 * @param memory Valid for Docker and Kubernetes - if memoryBacked is true, this parameter will be used as the size limit for the
 * memory backed volume
 */
data class Volume(
   val name: VolumeName,
   val mountDir: UnixDir,
   val hostDir: Path? = null, // Only valid for Docker
   val memoryBacked: Boolean = false,
   val memory: Memory? = null,
) {
   constructor(name: String, mountPath: String) : this(VolumeName.of(name), UnixDir.of(mountPath), null, false, null)

}