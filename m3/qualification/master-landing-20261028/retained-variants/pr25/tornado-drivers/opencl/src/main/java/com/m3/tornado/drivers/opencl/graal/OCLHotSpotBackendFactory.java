/*
 * Copyright (c) 2020-2022, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2018, 2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package com.m3.tornado.drivers.opencl.graal;

import static jdk.vm.ci.common.InitTimer.timer;
import static com.m3.tornado.api.exceptions.TornadoInternalError.shouldNotReachHere;

import com.m3.tornado.graal.compiler.api.replacements.SnippetReflectionProvider;
import com.m3.tornado.graal.compiler.core.common.spi.MetaAccessExtensionProvider;
import com.m3.tornado.graal.compiler.hotspot.meta.HotSpotStampProvider;
import com.m3.tornado.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import com.m3.tornado.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import com.m3.tornado.graal.compiler.nodes.loop.LoopsDataProviderImpl;
import com.m3.tornado.graal.compiler.nodes.spi.LoopsDataProvider;
import com.m3.tornado.graal.compiler.nodes.spi.LoweringProvider;
import com.m3.tornado.graal.compiler.nodes.spi.Replacements;
import com.m3.tornado.graal.compiler.options.OptionValues;
import com.m3.tornado.graal.compiler.phases.util.Providers;
import com.m3.tornado.graal.compiler.printer.GraalDebugHandlersFactory;
import com.m3.tornado.graal.compiler.replacements.StandardGraphBuilderPlugins;
import com.m3.tornado.graal.compiler.replacements.classfile.ClassfileBytecodeProvider;
import com.m3.tornado.graal.compiler.word.WordTypes;

import jdk.vm.ci.common.InitTimer;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.MetaAccessProvider;
import com.m3.tornado.drivers.opencl.OCLContextInterface;
import com.m3.tornado.drivers.opencl.OCLDeviceContextInterface;
import com.m3.tornado.drivers.opencl.OCLTargetDescription;
import com.m3.tornado.drivers.opencl.OCLTargetDevice;
import com.m3.tornado.drivers.opencl.graal.backend.OCLBackend;
import com.m3.tornado.drivers.opencl.graal.compiler.OCLCompilerConfiguration;
import com.m3.tornado.drivers.opencl.graal.compiler.plugins.OCLGraphBuilderPlugins;
import com.m3.tornado.drivers.opencl.graal.lir.OCLAddressLowering;
import com.m3.tornado.drivers.opencl.graal.lir.OCLKind;
import com.m3.tornado.drivers.providers.TornadoMetaAccessExtensionProvider;
import com.m3.tornado.drivers.providers.TornadoPlatformConfigurationProvider;
import com.m3.tornado.drivers.providers.TornadoWordTypes;
import com.m3.tornado.runtime.TornadoVMConfigAccess;
import com.m3.tornado.runtime.jvmci.TornadoConstantReflectionProvider;
import com.m3.tornado.runtime.jvmci.TornadoMetaAccessProvider;
import com.m3.tornado.runtime.graal.DummySnippetFactory;
import com.m3.tornado.runtime.graal.compiler.TornadoConstantFieldProvider;
import com.m3.tornado.runtime.graal.compiler.TornadoForeignCallsProvider;
import com.m3.tornado.runtime.graal.compiler.TornadoReplacements;
import com.m3.tornado.runtime.graal.compiler.TornadoSnippetReflectionProvider;

public class OCLHotSpotBackendFactory {

    private static final HotSpotStampProvider stampProvider = new HotSpotStampProvider();
    private static final TornadoSnippetReflectionProvider snippetReflection = new TornadoSnippetReflectionProvider();
    private static final TornadoForeignCallsProvider foreignCalls = new TornadoForeignCallsProvider();
    private static final TornadoConstantFieldProvider constantFieldProvider = new TornadoConstantFieldProvider();
    private static final OCLCompilerConfiguration compilerConfiguration = new OCLCompilerConfiguration();
    private static final OCLAddressLowering addressLowering = new OCLAddressLowering();

    public static OCLBackend createJITCompiler(OptionValues options, TornadoVMConfigAccess config, OCLContextInterface tornadoContext, OCLTargetDevice device) {
        // Type/constant metadata is served by the reflection + Unsafe providers on every JDK.
        MetaAccessProvider metaAccess = new TornadoMetaAccessProvider();
        ConstantReflectionProvider constantReflection = new TornadoConstantReflectionProvider(snippetReflection);

        OCLKind wordKind = switch (device.getWordSize()) {
            case 4 -> OCLKind.UINT;
            case 8 -> OCLKind.ULONG;
            default -> {
                shouldNotReachHere("unknown word size for device: word size is %d on %s", device.getWordSize(), device.getDeviceName());
                yield OCLKind.ILLEGAL;
            }
        };

        OCLArchitecture arch = new OCLArchitecture(wordKind, device.getByteOrder());
        OCLTargetDescription target = new OCLTargetDescription(arch, device.isDeviceDoubleFPSupported(), device.getDeviceExtensions());
        OCLCodeProvider codeCache = new OCLCodeProvider(target);
        OCLDeviceContextInterface oclDeviceContextImpl = (OCLDeviceContextInterface) tornadoContext.createDeviceContext(device.getIndex());

        OCLProviders providers;
        OCLLoweringProvider lowerer;
        OCLSuitesProvider suites;
        Plugins plugins;

        try (InitTimer t = timer("create providers")) {
            TornadoPlatformConfigurationProvider platformConfigurationProvider = new TornadoPlatformConfigurationProvider();
            MetaAccessExtensionProvider metaAccessExtensionProvider = new TornadoMetaAccessExtensionProvider();
            lowerer = new OCLLoweringProvider(metaAccess, foreignCalls, platformConfigurationProvider, metaAccessExtensionProvider, constantReflection, config, target);
            WordTypes wordTypes = new TornadoWordTypes(metaAccess, wordKind.asJavaKind());

            LoopsDataProvider lpd = new LoopsDataProviderImpl();
            Providers p = new Providers(metaAccess, codeCache, constantReflection, constantFieldProvider, foreignCalls, lowerer, lowerer.getReplacements(), stampProvider,
                    platformConfigurationProvider, metaAccessExtensionProvider, snippetReflection, wordTypes, lpd);
            ClassfileBytecodeProvider bytecodeProvider = new ClassfileBytecodeProvider(metaAccess, snippetReflection);
            GraalDebugHandlersFactory graalDebugHandlersFactory = new GraalDebugHandlersFactory(snippetReflection);
            TornadoReplacements replacements = new TornadoReplacements(graalDebugHandlersFactory, p, snippetReflection, bytecodeProvider, target);
            plugins = createGraphBuilderPlugins(metaAccess, replacements, snippetReflection, lowerer);

            replacements.setGraphBuilderPlugins(plugins);

            suites = new OCLSuitesProvider(options, oclDeviceContextImpl, plugins, metaAccess, compilerConfiguration, addressLowering);

            providers = new OCLProviders(metaAccess, codeCache, constantReflection, constantFieldProvider, foreignCalls, lowerer, replacements, stampProvider, platformConfigurationProvider,
                    metaAccessExtensionProvider, snippetReflection, wordTypes, p.getLoopsDataProvider(), suites);

            lowerer.initialize(options, new DummySnippetFactory(), providers);
        }
        try (InitTimer rt = timer("instantiate backend")) {
            return new OCLBackend(options, providers, target, codeCache, oclDeviceContextImpl);
        }
    }

    protected static Plugins createGraphBuilderPlugins(MetaAccessProvider metaAccess, Replacements replacements, SnippetReflectionProvider snippetReflectionProvider,
            LoweringProvider loweringProvider) {
        InvocationPlugins invocationPlugins = new InvocationPlugins();
        Plugins plugins = new Plugins(invocationPlugins);

        OCLGraphBuilderPlugins.registerParameterPlugins(plugins);
        OCLGraphBuilderPlugins.registerNewInstancePlugins(plugins);

        StandardGraphBuilderPlugins.registerInvocationPlugins(snippetReflectionProvider, //
                invocationPlugins, //
                replacements, //
                false, //
                false, //
                false, //
                loweringProvider);
        OCLGraphBuilderPlugins.registerInvocationPlugins(plugins, invocationPlugins, metaAccess);
        return plugins;
    }

    @Override
    public String toString() {
        return "OpenCL";
    }

}
