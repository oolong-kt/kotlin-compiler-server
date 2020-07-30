package indexation

import com.fasterxml.jackson.module.kotlin.isKotlinClass
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.internal.KotlinReflectionInternalError
import kotlin.reflect.jvm.kotlinFunction

object Main {
  private data class ImportInfo(val importName: String, val shortName: String, val fullName: String)

  private const val MODULE_INFO_NAME = "module-info"
  private const val EXECUTORS_JAR_NAME = "executors.jar"
  private const val JAR_EXTENSION = ".jar"
  private const val LIB_FOLDER_NAME = "lib"
  private const val CLASS_EXTENSION = ".class"

  private fun allClassesFromJavaClass(clazz: Class<*>): HashSet<ImportInfo> {
    val allClasses = hashSetOf<ImportInfo>()
    clazz.classes.filter {
      Modifier.isPublic(it.modifiers)
    }.map {
      val canonicalName = it.canonicalName
      val simpleName = it.simpleName
      val importInfo = ImportInfo(canonicalName, simpleName, simpleName)
      allClasses.add(importInfo)
    }
    return allClasses
  }

  private fun allClassesFromKotlinClass(clazz: Class<*>): HashSet<ImportInfo> {
    val allClasses = hashSetOf<ImportInfo>()
    val kotlinClass = clazz.kotlin
    try {
      kotlinClass.nestedClasses.filter {
        it.visibility == KVisibility.PUBLIC
      }.map {
        val canonicalName = it.qualifiedName ?: ""
        val simpleName = it.simpleName ?: ""
        val importInfo = ImportInfo(canonicalName, simpleName, simpleName)
        allClasses.add(importInfo)
      }
      if (kotlinClass.visibility == KVisibility.PUBLIC) {
        val canonicalName = kotlinClass.qualifiedName ?: ""
        val simpleName = kotlinClass.simpleName ?: ""
        val importInfo = ImportInfo(canonicalName, simpleName, simpleName)
        allClasses.add(importInfo)
      }
    } catch (exception: UnsupportedOperationException) {
      return allClassesFromJavaClass(clazz)
    } catch (error: IncompatibleClassChangeError) {
      /*
      INCOMP_ERR: kotlin.sequences.SequencesKt___SequencesKt$scan$1
      INCOMP_ERR: kotlin.sequences.SequencesKt___SequencesKt$scanReduce$1
      INCOMP_ERR: kotlin.sequences.SequencesKt___SequencesKt$scanIndexed$1
      INCOMP_ERR: kotlin.sequences.SequencesKt___SequencesKt$scanReduceIndexed$1
      INCOMP_ERR: kotlin.jvm.internal.ClassReference$Companion
    */
      return allClassesFromJavaClass(clazz)
    } catch (exception: NoSuchElementException) {
      /*
    NO_SUCH_ERR: kotlinx.coroutines.flow.internal.ChannelFlowKt$withContextUndispatched$$inlined$suspendCoroutine
      UninterceptedOrReturn$lambda$1
    NO_SUCH_ERR: kotlin.coroutines.experimental.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineUnchecked
      $$inlined$buildContinuationByInvokeCall$IntrinsicsKt__IntrinsicsJvmKt$1
    NO_SUCH_ERR: kotlin.coroutines.experimental.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineUnchecked
      $$inlined$buildContinuationByInvokeCall$IntrinsicsKt__IntrinsicsJvmKt$2
    */
      return allClassesFromJavaClass(clazz)
    }
    return allClasses
  }

  private fun initClasspath(taskRoot: String): List<URL> {
    val cwd = File(taskRoot)
    val classPath = mutableListOf(cwd)
    val rootFiles =
      cwd.listFiles { _: File?, name: String -> name.endsWith(JAR_EXTENSION) || name == LIB_FOLDER_NAME }
        ?: error("No files found from $taskRoot directory")
    for (file in rootFiles) {
      if (file.name == LIB_FOLDER_NAME && file.isDirectory) {
        val libFolderFiles =
          file.listFiles { _: File?, name: String -> name.endsWith(JAR_EXTENSION) } ?: continue
        for (jar in libFolderFiles) {
          classPath.add(jar)
        }
      } else {
        classPath.add(file)
      }
    }
    return classPath.mapNotNull { it.toURI().toURL() }
  }

  private fun getVariantsForZip(classLoader: URLClassLoader, file: File): List<ImportInfo> {
    val jarFile = JarFile(file)
    val allSuggests = hashSetOf<ImportInfo>()
    jarFile.entries().toList().map { entry ->
      if (!entry.isDirectory
        && entry.name.endsWith(CLASS_EXTENSION)
      ) {
        val name = entry.name.removeSuffix(CLASS_EXTENSION)
        val fullName = name.replace(File.separator, ".")
        if (fullName != MODULE_INFO_NAME) {
            val clazz = classLoader.loadClass(fullName) ?: return emptyList()
            if (clazz.isKotlinClass()) {
              allSuggests.addAll(allClassesFromKotlinClass(clazz))
            } else {
              allSuggests.addAll(allClassesFromJavaClass(clazz))
            }
            allSuggests.addAll(allFunctionsFromClass(clazz))

        }
      }
    }
    return allSuggests.toList()
  }

  private fun allFunctionsFromClass(clazz: Class<*>): HashSet<ImportInfo> {
    val allClasses = hashSetOf<ImportInfo>()
    clazz.declaredMethods.map { method ->
      val importInfo = importInfoFromFunction(method, clazz)
      if (importInfo != null) allClasses.add(importInfo)
    }
    return allClasses
  }

  private fun importInfoFromFunction(method: Method, clazz: Class<*>): ImportInfo? {
    var kotlinFunction: KFunction<*>? = null
    try {
      kotlinFunction = method.kotlinFunction
    } catch (exception: NoSuchElementException) {
    } catch (exception: UnsupportedOperationException) {
    } catch (error: KotlinReflectionInternalError) {
    } catch (error: IncompatibleClassChangeError) {
    }
    return if (kotlinFunction != null
      && kotlinFunction.visibility == KVisibility.PUBLIC) {
      importInfoByMethodAndParent(
        kotlinFunction.name,
        kotlinFunction.parameters.map { it.type }.joinToString(),
        clazz)
    } else importInfoFromJavaMethod(method, clazz)
  }

  private fun importInfoFromJavaMethod(method: Method, clazz: Class<*>): ImportInfo? =
    if (Modifier.isPublic(method.modifiers) &&
      Modifier.isStatic(method.modifiers))
      importInfoByMethodAndParent(method.name, method.parameters.joinToString { it.type.name }, clazz)
    else null

  private fun importInfoByMethodAndParent(methodName: String, parametersString: String, parent: Class<*>): ImportInfo {
    val shortName = methodName.split("$").first()
    val className = "$shortName($parametersString)"
    val importName = "${parent.`package`.name}.$shortName"
    return ImportInfo(importName, shortName, className)
  }

  private fun getAllVariants(classLoader: URLClassLoader, files: List<File>): List<ImportInfo> {
    val jarFiles = files.filter { jarFile ->
      jarFile.name.split(File.separator).last() != EXECUTORS_JAR_NAME
    }
    val allVariants = mutableListOf<ImportInfo>()
    jarFiles.map { file ->
      val variants = getVariantsForZip(classLoader, file)
      allVariants.addAll(variants)
    }
    return allVariants
  }

  private fun createJsonWithIndexes(directoryPath: String, outputPath: String) {
    val file = File(directoryPath)
    val filesArr = file.listFiles()
    val files = filesArr.toList()
    val classPathUrls = initClasspath(directoryPath)
    val classLoader = URLClassLoader.newInstance(classPathUrls.toTypedArray())
    File(outputPath).writeText("")

    val mapper = jacksonObjectMapper()
    File(outputPath).appendText(mapper.writeValueAsString(getAllVariants(classLoader, files)))
  }

  @JvmStatic
  // First argument is path to folder with jars
  // Second argument is path to output file
  fun main(args: Array<String>) {
    val directory = args[0]
    val outputPath = args[1]
    createJsonWithIndexes(directory, outputPath)
  }
}