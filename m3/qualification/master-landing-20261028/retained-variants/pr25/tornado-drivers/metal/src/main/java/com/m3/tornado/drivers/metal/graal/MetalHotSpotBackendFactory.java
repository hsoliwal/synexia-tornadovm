/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2026, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
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
package com.m3.tornado.drivers.metal.graal;

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
import com.m3.tornado.drivers.metal.MetalContextInterface;
import com.m3.tornado.drivers.metal.MetalDeviceContextInterface;
import com.m3.tornado.drivers.metal.MetalTargetDescription;
import com.m3.tornado.drivers.metal.MetalTargetDevice;
import com.m3.tornado.drivers.metal.graal.backend.MetalBackend;
import com.m3.tornado.drivers.metal.graal.compiler.MetalCompilerConfiguration;
import com.m3.tornado.drivers.metal.graal.compiler.plugins.MetalGraphBuilderPlugins;
import com.m3.tornado.drivers.metal.graal.lir.MetalAddressLowering;
import com.m3.tornado.drivers.metal.graal.lir.MetalKind;
import com.m3.tornado.drivers.providers.TornadoMetaAccessExtensionProvider;
import com.m3.tornado.drivers.providers.TornadoPlatformConfigurationProvider;
import com.m3.tornado.drivers.providers.TornadoWordTypes;
import com.m3.tornado.runtime.TornadoVMConfigAccess;
import com.m3.tornado.runtime.graal.DummySnippetFactory;
import com.m3.tornado.runtime.graal.compiler.TornadoConstantFieldProvider;
import com.m3.tornado.runtime.graal.compiler.TornadoForeignCallsProvider;
import com.m3.tornado.runtime.graal.compiler.TornadoReplacements;
import com.m3.tornado.runtime.graal.compiler.TornadoSnippetReflectionProvider;
import com.m3.tornado.runtime.jvmci.TornadoConstantReflectionProvider;
import com.m3.tornado.runtime.jvmci.TornadoMetaAccessProvider;

public class MetalHotSpotBackendFactory {

    private static final HotSpotStampProvider stampProvider = new HotSpotStampProvider();
    private static final TornadoSnippetReflectionProvider snippetReflection = new TornadoSnippetReflectionProvider();
    private static final TornadoForeignCallsProvider foreignCalls = new TornadoForeignCallsProvider();
    private static final TornadoConstantFieldProvider constantFieldProvider = new TornadoConstantFieldProvider();
    private static final MetalCompilerConfiguration compilerConfiguration = new MetalCompilerConfiguration();
    private static final MetalAddressLowering addressLowering = new MetalAddressLowering();

    public static MetalBackend createJITCompiler(OptionValues options, TornadoVMConfigAccess config, MetalContextInterface tornadoContext, MetalTargetDevice device) {
        MetaAccessProvider metaAccess = new TornadoMetaAccessProvider();
        ConstantReflectionProvider constantReflection = new TornadoConstantReflectionProvider(snippetReflection);

        MetalKind wordKind = switch (device.getWordSize()) {
            case 4 -> MetalKind.UINT;
            case 8 -> MetalKind.ULONG;
            default -> {
                shouldNotReachHere("unknown word size for device: word size is %d on %s", device.getWordSize(), device.getDeviceName());
                yield MetalKind.ILLEGAL;
            }
        };

        MetalArchitecture arch = new MetalArchitecture(wordKind, device.getByteOrder());
        MetalTargetDescription target = new MetalTargetDescription(arch, device.isDeviceDoubleFPSupported(), device.isDeviceFP16Supported(), device.isDeviceInt64AtomicsSupported());
        MetalCodeProvider codeCache = new MetalCodeProvider(target);
        MetalDeviceContextInterface metalDeviceContextImpl = (MetalDeviceContextInterface) tornadoContext.createDeviceContext(device.getIndex());

        MetalProviders providers;
        MetalLoweringProvider lowerer;
        MetalSuitesProvider suites;
        Plugins plugins;

        try (InitTimer t = timer("create providers")) {
            TornadoPlatformConfigurationProvider platformConfigurationProvider = new TornadoPlatformConfigurationProvider();
            MetaAccessExtensionProvider metaAccessExtensionProvider = new TornadoMetaAccessExtensionProvider();
            lowerer = new MetalLoweringProvider(metaAccess, foreignCalls, platformConfigurationProvider, metaAccessExtensionProvider, constantReflection, config, target);
            WordTypes wordTypes = new TornadoWordTypes(metaAccess, wordKind.asJavaKind());

            LoopsDataProvider lpd = new LoopsDataProviderImpl();
            Providers p = new Providers(metaAccess, codeCache, constantReflection, constantFieldProvider, foreignCalls, lowerer, lowerer.getReplacements(), stampProvider,
                    platformConfigurationProvider, metaAccessExtensionProvider, snippetReflection, wordTypes, lpd);
            ClassfileBytecodeProvider bytecodeProvider = new ClassfileBytecodeProvider(metaAccess, snippetReflection);
            GraalDebugHandlersFactory graalDebugHandlersFactory = new GraalDebugHandlersFactory(snippetReflection);
            TornadoReplacements replacements = new TornadoReplacements(graalDebugHandlersFactory, p, snippetReflection, bytecodeProvider, target);
            plugins = createGraphBuilderPlugins(metaAccess, replacements, snippetReflection, lowerer);

            replacements.setGraphBuilderPlugins(plugins);

            suites = new MetalSuitesProvider(options, metalDeviceContextImpl, plugins, metaAccess, compilerConfiguration, addressLowering);

            providers = new MetalProviders(metaAccess, codeCache, constantReflection, constantFieldProvider, foreignCalls, lowerer, replacements, stampProvider, platformConfigurationProvider,
                    metaAccessExtensionProvider, snippetReflection, wordTypes, p.getLoopsDataProvider(), suites);

            lowerer.initialize(options, new DummySnippetFactory(), providers);
        }
        try (InitTimer rt = timer("instantiate backend")) {
            return new MetalBackend(options, providers, target, codeCache, metalDeviceContextImpl);
        }
    }

    protected static Plugins createGraphBuilderPlugins(MetaAccessProvider metaAccess, Replacements replacements, SnippetReflectionProvider snippetReflectionProvider,
            LoweringProvider loweringProvider) {
        InvocationPlugins invocationPlugins = new InvocationPlugins();
        Plugins plugins = new Plugins(invocationPlugins);

        MetalGraphBuilderPlugins.registerParameterPlugins(plugins);
        MetalGraphBuilderPlugins.registerNewInstancePlugins(plugins);

        StandardGraphBuilderPlugins.registerInvocationPlugins(snippetReflectionProvider, //
                invocationPlugins, //
                replacements, //
                false, //
                false, //
                false, //
                loweringProvider);
        MetalGraphBuilderPlugins.registerInvocationPlugins(plugins, invocationPlugins);
        return plugins;
    }

    @Override
    public String toString() {
        return "Metal";
    }

}
