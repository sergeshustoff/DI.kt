# DI.kt
Simple and powerful DI for kotlin multiplatform

#Work in progress

## Installation

not published yet

    buildscript {
        repositories {
            mavenCentral()
        }
        dependencies {
            classpath "io.github.sergeshustoff:dikt-gradle-plugin:1.0.0-alpha1"
        }
    }
    
    apply plugin: 'io.github.sergeshustoff.dikt'
    
## Usage

Create module and declare provided dependencies. Use @ByDi to mark properties and functions to be autogenerated.

    @Module
    class CarModule(
        val externalDependency: Something,
    ) {
        @ByDi val someSingleton: SomeSingleton
        @ByDi fun provideSomethingElse(): SomethingElse
    }
  
Under the hood constructor with @Inject annotation will be called for SomethingElse and SomeSingleton. If there is no annotated constructor - primary constructor is used for direct dependency. If constructor requires some parameters - they will be retrieved form module properties, nested modules, module functions or created by a constructor with @Inject annotation.
