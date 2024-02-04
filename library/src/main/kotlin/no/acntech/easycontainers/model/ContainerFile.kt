package no.acntech.easycontainers.model

import java.nio.file.Path

/**
 * Value object representing a file to be added or mapped into a container.
 * <ul>
 *  <li>For Docker this becomes a <i>bind mount</i>, and the content parameter will be ignored if the localFile parameter
 *  is set.</li>
 *
 *  <li> For Docker, if the localFile parameter is not set, the content parameter must be set and EasyContainers will create a
 *  temporary file with the contents of the 'content' parameter and use that as the source for the bind mount.</li>
 *
 *  <li>Note that a in Docker a bind mount is a synchronization mechanism between the host and the container, and any changes to
 *  the file made by any of the parties will be reflected in the other party.</li>
 *
 *  <li>For Kubernetes an object of this class becomes a <i>ConfigMap</i> and the 'content' parameter will be used to populate the
 *  contents of the key mapped to a file. Note that Kubernetes implementation will use the individual mount file approach
 *  hence avoiding obscuring an existing directory in the container's file system. </li>
 * </ul>
 *
 * @param mountPath The path in the container where the file will be mounted.
 * @param content Optional content - valid for both Docker and Kubernetes
 * @param hostFile Only valid for Docker - if set, the content parameter will be ignored
 */
data class ContainerFile(
   val containerFileName: ContainerFileName,
   val mountPath: UnixDir,
   val content: String? = null,
   val hostFile: Path? = null
)