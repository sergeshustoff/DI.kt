package dev.shustoff.dikt.core

import dev.shustoff.dikt.dependency.Dependency
import dev.shustoff.dikt.message_collector.ErrorCollector
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import java.util.*

class InjectionDependencyCollector(
    private val errorCollector: ErrorCollector
) {
    fun collectDependencies(
        rootModule: IrClass
    ): ModuleDependencies {
        val fullDependencyMap: MutableMap<DependencyId, MutableList<Dependency>> = mutableMapOf()
        rootModule.properties
            .forEach {
                val dependency = Dependency.Property(it, null)
                fullDependencyMap.getOrPut(dependency.id) { mutableListOf() }.add(dependency)
            }

        rootModule.functions.forEach {
            createFunctionDependency(it)?.let { dependency ->
                fullDependencyMap.getOrPut(dependency.id) { mutableListOf() }.add(dependency)
            }
        }

        val modules = LinkedList(fullDependencyMap.values.flatten()
            .mapNotNull {
                getModuleClassDescriptor(it)
                    ?.let { classDescriptor -> Module(it, classDescriptor) }
            }
            .toList()
        )

        while (modules.isNotEmpty()) {
            val module = modules.pop()
            val dependencies = module.clazz.properties
                .filter { it.isVisible(rootModule) }
                .map { Dependency.Property(it, module.path, returnType = module.typeMap[it.getter!!.returnType] ?: it.getter!!.returnType) } +
                    module.clazz.functions
                        .filter { it.isVisible(rootModule) }
                        .mapNotNull { createFunctionDependency(it, module) }

            val withoutDuplicates = dependencies
                .filter { fullDependencyMap[it.id]?.any { it.fromNestedModule != null } != true }
                .toList()

            withoutDuplicates.forEach { dependency ->
                fullDependencyMap.getOrPut(dependency.id) { mutableListOf() }.add(dependency)
            }

            modules.addAll(
                withoutDuplicates.mapNotNull {
                    getModuleClassDescriptor(it)
                        ?.let { classDescriptor -> Module(it, classDescriptor) }
                }.toList()
            )
        }

        return ModuleDependencies(
            errorCollector,
            rootModule,
            fullDependencyMap
        )
    }

    private fun createFunctionDependency(it: IrSimpleFunction, module: Module? = null): Dependency.Function? {
        if (!isDependencyFunction(it)) return null
        return Dependency.Function(it, module?.path, returnType = module?.typeMap?.get(it.returnType) ?: it.returnType)
    }

    private fun isDependencyFunction(it: IrSimpleFunction) =
        !it.isFakeOverride && !it.isOperator && !it.isSuspend && !it.isInfix && !it.returnType.isUnit() && !it.returnType.isNothing()

    private fun getModuleClassDescriptor(dependency: Dependency) =
        dependency.id.type.getClass()?.takeIf { Annotations.isModule(it) }

    private data class Module(
        val path: Dependency,
        val clazz: IrClass,
    ) {
        val typeMap: Map<IrType?, IrType?> by lazy {
            val typeArguments = (path.id.type as? IrSimpleType)?.arguments?.map { it as? IrType }
            val dependencyTypeArguments = (clazz.defaultType as? IrSimpleType)?.arguments?.map { it as? IrType }
            dependencyTypeArguments?.zip(typeArguments.orEmpty())?.toMap().orEmpty()
        }
    }
}