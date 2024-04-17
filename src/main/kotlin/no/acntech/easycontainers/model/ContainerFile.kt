package no.acntech.easycontainers.model

import java.nio.file.Path

/**
 * Value object representing a file to be added or mapped into a container.
 * <ul>
 *  <li>For Docker this becomes a <i>bind mount</i>, and if hostFile is set, the file will be mounted from the host, otherwise
 *  a temporary file will be as the source for the bind mount. Either way the file will be populated with 'content' if set</li>
 *
 *  <li>Note that a in Docker a bind mount is a synchronization mechanism between the host and the container, and any changes to
 *  the file made by any of the parties will be reflected in the other party.</li>
 *
 *  <li>For Kubernetes an object of this class becomes a <i>ConfigMap</i> and the 'content' parameter will be used to populate the
 *  contents of the key mapped to a file. Note that Kubernetes implementation will use the individual mount file approach
 *  hence avoiding obscuring an existing directory in the container's file system. </li>
 * </ul>
 *
 * @param name The name of the file in the container.
 * @param mountPath The path in the container where the file will be mounted.
 * @param content Optional content - valid for both Docker and Kubernetes - if set, the mounted file will be populated with this
 *                content.
 * @param hostFile Only valid for Docker - if not set a temporary file will be created and used as the source for the bind mount.
 */
data class ContainerFile(
   val name: ContainerFileName,
   val mountPath: UnixDir,
   val content: String? = null,
   val hostFile: Path? = null,
)