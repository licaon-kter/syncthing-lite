/* 
 * Copyright (C) 2016 Davide Imbriaco
 *
 * This Java file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.syncthing.java.client.cli

import net.syncthing.java.core.beans.DeviceId
import net.syncthing.java.core.beans.DeviceInfo
import net.syncthing.java.core.beans.FileInfo
import net.syncthing.java.core.configuration.Configuration
import net.syncthing.java.repository.repo.SqlRepository
import net.syncthing.java.client.SyncthingClient
import org.apache.commons.cli.*
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch

class Main(private val commandLine: CommandLine) {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val options = generateOptions()
            val parser = DefaultParser()
            val cmd = parser.parse(options, args)
            if (cmd.hasOption("h")) {
                val formatter = HelpFormatter()
                formatter.printHelp("s-client", options)
                return
            }
            val configuration = if (cmd.hasOption("C")) Configuration(File(cmd.getOptionValue("C")))
            else                                        Configuration()

            val repository = SqlRepository(configuration.databaseFolder)

            SyncthingClient(configuration, repository, repository).use { syncthingClient ->
                val main = Main(cmd)
                cmd.options.forEach { main.handleOption(it, configuration, syncthingClient) }
            }
        }

        private fun generateOptions(): Options {
            val options = Options()
            options.addOption("C", "set-config", true, "set config file for s-client")
            options.addOption("c", "config", false, "dump config")
            options.addOption("S", "set-peers", true, "set peer, or comma-separated list of peers")
            options.addOption("p", "pull", true, "pull file from network")
            options.addOption("P", "push", true, "push file to network")
            options.addOption("o", "output", true, "set output file/directory")
            options.addOption("i", "input", true, "set input file/directory")
            options.addOption("a", "list-peers", false, "list peer addresses")
            options.addOption("a", "address", true, "use this peer addresses")
            options.addOption("L", "list-remote", false, "list folder (root) content from network")
            options.addOption("I", "list-info", false, "dump folder info from network")
            options.addOption("l", "list-info", false, "list folder info from local db")
            options.addOption("D", "delete", true, "push delete to network")
            options.addOption("M", "mkdir", true, "push directory create to network")
            options.addOption("h", "help", false, "print help")
            return options
        }
    }

    private val logger = LoggerFactory.getLogger(Main::class.java)

    private fun handleOption(option: Option, configuration: Configuration, syncthingClient: SyncthingClient) {
        when (option.opt) {
            "S" -> {
                val peers = option.value
                        .split(",")
                        .filterNot { it.isEmpty() }
                        .map { DeviceId(it.trim()) }
                        .toList()
                System.out.println("set peers = $peers")
                configuration.peers = peers.map { DeviceInfo(it, null) }.toSet()
                configuration.persistNow()
            }
            "p" -> {
                val folderAndPath = option.value
                System.out.println("file path = $folderAndPath")
                val folder = folderAndPath.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
                val path = folderAndPath.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[1]
                val latch = CountDownLatch(1)
                val fileInfo = FileInfo(folder = folder, path = path, type = FileInfo.FileType.FILE)
                syncthingClient.getBlockPuller(folder, { blockPuller ->
                    try {
                        val inputStream = blockPuller.pullFileSync(fileInfo)
                        val fileName = syncthingClient.indexHandler.getFileInfoByPath(folder, path)!!.fileName
                        val file  =
                                if (commandLine.hasOption("o")) {
                                    val param = File(commandLine.getOptionValue("o"))
                                    if (param.isDirectory) File(param, fileName) else param
                                } else {
                                    File(fileName)
                                }
                        FileUtils.copyInputStreamToFile(inputStream, file)
                        System.out.println("saved file to = $file.absolutePath")
                    } catch (e: InterruptedException) {
                        logger.warn("", e)
                    } catch (e: IOException) {
                        logger.warn("", e)
                    }
                }, { logger.warn("Failed to pull file") })
                latch.await()
            }
            "P" -> {
                var path = option.value
                val file = File(commandLine.getOptionValue("i"))
                assert(!path.startsWith("/")) //TODO check path syntax
                System.out.println("file path = $path")
                val folder = path.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
                path = path.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[1]
                val latch = CountDownLatch(1)
                syncthingClient.getBlockPusher(folder, { blockPusher ->
                    val observer = blockPusher.pushFile(FileInputStream(file), folder, path)
                    while (!observer.isCompleted()) {
                        try {
                            observer.waitForProgressUpdate()
                        } catch (e: InterruptedException) {
                            logger.warn("", e)
                        }

                        System.out.println("upload progress ${observer.progressPercentage()}%")
                    }
                    latch.countDown()
                }, { logger.warn("Failed to upload file") })
                latch.await()
                System.out.println("uploaded file to network")
            }
            "D" -> {
                var path = option.value
                val folder = path.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
                path = path.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[1]
                System.out.println("delete path = $path")
                val latch = CountDownLatch(1)
                syncthingClient.getBlockPusher(folder, { blockPusher ->
                    try {
                        blockPusher.pushDelete(folder, path).waitForComplete()
                    } catch (e: InterruptedException) {
                        logger.warn("", e)
                    }

                    latch.countDown()
                }, { System.out.println("Failed to delete path") })
                latch.await()
                System.out.println("deleted path")
            }
            "M" -> {
                var path = option.value
                val folder = path.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
                path = path.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[1]
                System.out.println("dir path = $path")
                val latch = CountDownLatch(1)
                syncthingClient.getBlockPusher(folder, { blockPusher ->
                    try {
                        blockPusher.pushDir(folder, path).waitForComplete()
                    } catch (e: InterruptedException) {
                        logger.warn("", e)
                    }

                    latch.countDown()
                }, { System.out.println("Failed to push directory") })
                latch.await()
                System.out.println("uploaded dir to network")
            }
            "L" -> {
                waitForIndexUpdate(syncthingClient, configuration)
                for (folder in syncthingClient.indexHandler.folderList()) {
                    syncthingClient.indexHandler.newIndexBrowser(folder).use { indexBrowser ->
                        System.out.println("list folder = ${indexBrowser.folder}")
                        for (fileInfo in indexBrowser.listFiles()) {
                            System.out.println("${fileInfo.type.name.substring(0, 1)}\t${fileInfo.describeSize()}\t${fileInfo.path}")
                        }
                    }
                }
            }
            "I" -> {
                waitForIndexUpdate(syncthingClient, configuration)
                val folderInfo = StringBuilder()
                for (folder in syncthingClient.indexHandler.folderList()) {
                    folderInfo.append("\nfolder info: ")
                            .append(syncthingClient.indexHandler.getFolderInfo(folder))
                    folderInfo.append("\nfolder stats: ")
                            .append(syncthingClient.indexHandler.newFolderBrowser().getFolderStats(folder).dumpInfo())
                            .append("\n")
                }
                System.out.println("folders:\n$folderInfo\n")
            }
            "l" -> {
                var folderInfo = ""
                for (folder in syncthingClient.indexHandler.folderList()) {
                    folderInfo += "\nfolder info: " + syncthingClient.indexHandler.getFolderInfo(folder)
                    folderInfo += "\nfolder stats: " + syncthingClient.indexHandler.newFolderBrowser().getFolderStats(folder).dumpInfo() + "\n"
                }
                System.out.println("folders:\n$folderInfo\n")
            }
            "a" -> {
                val deviceAddressSupplier = syncthingClient.discoveryHandler.newDeviceAddressSupplier()
                var deviceAddressesStr = ""
                for (deviceAddress in deviceAddressSupplier.toList()) {
                    deviceAddressesStr += "\n" + deviceAddress?.deviceId + " : " + deviceAddress?.address
                }
                System.out.println("device addresses:\n$deviceAddressesStr\n")
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun waitForIndexUpdate(client: SyncthingClient, configuration: Configuration) {
        val latch = CountDownLatch(configuration.peers.size)
        client.indexHandler.registerOnFullIndexAcquiredListenersListener {
            latch.countDown()
        }
        latch.await()
    }
}
