package dev.shustoff.dikt.core

import dev.shustoff.dikt.dependency.Dependency
import dev.shustoff.dikt.dependency.ResolvedDependency
import dev.shustoff.dikt.message_collector.ErrorCollector
import org.jetbrains.kotlin.backend.jvm.codegen.psiElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor

class ModuleDependencies(
    errorCollector: ErrorCollector,
    private val module: IrClass,
    private val dependencyMap: Map<DependencyId, List<Dependency>>
) : ErrorCollector by errorCollector {

    fun resolveDependency(
        type: IrType,
        forProperty: IrProperty
    ): ResolvedDependency? = resolveDependencyInternal(DependencyId(type), forProperty, emptySet(),
        allowConstructorWithoutAnnotation = true)

    private fun resolveDependencyInternal(
        id: DependencyId,
        injectionProperty: IrProperty,
        usedTypes: Set<IrType>,
        allowConstructorWithoutAnnotation: Boolean = false
    ): ResolvedDependency? {
        return findDependency(id, injectionProperty, module, usedTypes, allowConstructorWithoutAnnotation)
            ?.let { dependency ->
                val params = dependency.getRequiredParams()
                val typeArgumentsMapping = buildTypeArgumentsMapping(id, dependency)
                getResolveParams(params, injectionProperty, usedTypes + id.type, typeArgumentsMapping)
                    ?.let {
                        ResolvedDependency(dependency, it)
                    }
            }
    }

    private fun buildTypeArgumentsMapping(
        id: DependencyId,
        dependency: Dependency
    ): Map<IrType?, IrType?> {
        val typeArguments = (id.type as? IrSimpleType)?.arguments?.map { it as? IrType }
        val dependencyTypeArguments = (dependency.id.type as? IrSimpleType)?.arguments?.map { it as? IrType }
        return dependencyTypeArguments.orEmpty().zip(typeArguments.orEmpty()).toMap()
    }

    private fun getResolveParams(
        valueParameters: List<IrValueParameter>,
        injectionProperty: IrProperty,
        usedTypes: Set<IrType> = emptySet(),
        typeArgumentsMapping: Map<IrType?, IrType?>,
    ): List<ResolvedDependency>? {
        return valueParameters
            .mapNotNull { param ->
                resolveDependencyInternal(
                    DependencyId(typeArgumentsMapping[param.type] ?: param.type,
                        Annotations.getAnnotatedName(param).orEmpty()),
                    injectionProperty,
                    usedTypes,
                )
            }
            .takeIf { it.size == valueParameters.size } // errors reported in findDependency
    }

    private fun findDependency(
        id: DependencyId,
        injectionProperty: IrProperty,
        module: IrClass,
        usedTypes: Set<IrType> = emptySet(),
        allowConstructorWithoutAnnotation: Boolean = false
    ): Dependency? {
        if (id.type in usedTypes) {
            injectionProperty.psiElement.error(
                "Recursive dependency in ${id.asErrorString()} needed to initialize property ${injectionProperty.name.asString()} in module ${module.name.asString()}",
            )
            return null
        }

        val propertyDependencyOptions = dependencyMap[id]
        val propertyCount = propertyDependencyOptions?.count { it is Dependency.Property } ?: 0
        val functionCount = propertyDependencyOptions?.count { it is Dependency.Function } ?: 0
        if (propertyCount > 1 || (propertyCount == 0 && functionCount > 1)) {
            injectionProperty.psiElement.error(
                "Multiple dependencies provided with type ${id.asErrorString()} in module ${module.name.asString()}",
            )
        }
        val first = propertyDependencyOptions
            ?.firstOrNull { it !is Dependency.Property || it.property != injectionProperty }
        if (first != null) {
            return first
        }

        val constructor = findConstructorInjector(id, allowConstructorWithoutAnnotation)
        if (constructor == null || !constructor.isVisible(module)) {
            injectionProperty.psiElement.error(
                "Can't resolve dependency ${id.asErrorString()} needed to initialize property ${injectionProperty.name.asString()} in module ${module.name.asString()}",
            )
            return null
        }
        return Dependency.Constructor(constructor)
    }

    private fun findConstructorInjector(id: DependencyId, allowConstructorWithoutAnnotation: Boolean): IrConstructor? {
        return id.type.getClass()
            ?.takeIf { id.name.isEmpty() }
            ?.let { clazz ->
                clazz.constructors.firstOrNull { it.hasAnnotation(Annotations.injectAnnotation) }
                    ?: clazz.takeIf { it.hasAnnotation(Annotations.injectAnnotation) || allowConstructorWithoutAnnotation }?.primaryConstructor
            }
    }
}