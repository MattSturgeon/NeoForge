/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     cpw - implementation
 */

package cpw.mods.fml.common.asm.transformers.deobf;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.ImmutableBiMap.Builder;
import com.google.common.io.CharStreams;
import com.google.common.io.InputSupplier;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.RelaunchClassLoader;

public class FMLDeobfuscatingRemapper extends Remapper {
    public static final FMLDeobfuscatingRemapper INSTANCE = new FMLDeobfuscatingRemapper();

    private BiMap<String, String> classNameBiMap;
    private Map<String,Map<String,String>> rawFieldMaps;
    private Map<String,Map<String,String>> rawMethodMaps;

    private Map<String,Map<String,String>> fieldNameMaps;
    private Map<String,Map<String,String>> methodNameMaps;

    private RelaunchClassLoader classLoader;

    private FMLDeobfuscatingRemapper()
    {
        classNameBiMap=ImmutableBiMap.of();
    }

    public void setup(File mcDir, RelaunchClassLoader classLoader, String deobfFileName)
    {
        this.classLoader = classLoader;
        try
        {
            File libDir = new File(mcDir, "lib");
            File mapData = new File(libDir, deobfFileName);
            ZipFile mapZip = new ZipFile(mapData);
            ZipEntry classData = mapZip.getEntry("joined.srg");
            ZipInputSupplier zis = new ZipInputSupplier(mapZip, classData);
            InputSupplier<InputStreamReader> srgSupplier = CharStreams.newReaderSupplier(zis,Charsets.UTF_8);
            List<String> srgList = CharStreams.readLines(srgSupplier);
            rawMethodMaps = Maps.newHashMap();
            rawFieldMaps = Maps.newHashMap();
            Builder<String, String> builder = ImmutableBiMap.<String,String>builder();
            Splitter splitter = Splitter.on(CharMatcher.anyOf(": ")).omitEmptyStrings().trimResults();
            for (String line : srgList)
            {
                String[] parts = Iterables.toArray(splitter.split(line),String.class);
                String typ = parts[0];
                if ("CL".equals(typ))
                {
                    parseClass(builder, parts);
                }
                else if ("MD".equals(typ))
                {
                    parseMethod(parts);
                }
                else if ("FD".equals(typ))
                {
                    parseField(parts);
                }
            }
            classNameBiMap = builder.build();

        }
        catch (IOException ioe)
        {
            FMLRelaunchLog.log(Level.SEVERE, ioe, "An error occurred loading the deobfuscation map data");
        }
        methodNameMaps = Maps.newHashMapWithExpectedSize(rawMethodMaps.size());
        fieldNameMaps = Maps.newHashMapWithExpectedSize(rawFieldMaps.size());
    }

    public boolean isRemappedClass(String className)
    {
        return classNameBiMap.containsKey(className);
    }

    private void parseField(String[] parts)
    {
        String oldSrg = parts[1];
        int lastOld = oldSrg.lastIndexOf('/');
        String cl = oldSrg.substring(0,lastOld);
        String oldName = oldSrg.substring(lastOld+1);
        String newSrg = parts[2];
        int lastNew = newSrg.lastIndexOf('/');
        String newName = newSrg.substring(lastNew+1);
        if (!rawFieldMaps.containsKey(cl))
        {
            rawFieldMaps.put(cl, Maps.<String,String>newHashMap());
        }
        rawFieldMaps.get(cl).put(oldName, newName);
    }

    private void parseClass(Builder<String, String> builder, String[] parts)
    {
        builder.put(parts[1],parts[2]);
    }

    private void parseMethod(String[] parts)
    {
        String oldSrg = parts[1];
        int lastOld = oldSrg.lastIndexOf('/');
        String cl = oldSrg.substring(0,lastOld);
        String oldName = oldSrg.substring(lastOld+1);
        String sig = parts[2];
        String newSrg = parts[3];
        int lastNew = newSrg.lastIndexOf('/');
        String newName = newSrg.substring(lastNew+1);
        if (!rawMethodMaps.containsKey(cl))
        {
            rawMethodMaps.put(cl, Maps.<String,String>newHashMap());
        }
        rawMethodMaps.get(cl).put(oldName+sig, newName);
    }

    @Override
    public String mapFieldName(String owner, String name, String desc)
    {
        if (classNameBiMap == null || classNameBiMap.isEmpty())
        {
            return name;
        }
        Map<String, String> fieldMap = getFieldMap(owner);
        return fieldMap!=null && fieldMap.containsKey(name) ? fieldMap.get(name) : name;
    }

    @Override
    public String map(String typeName)
    {
        if (classNameBiMap == null || classNameBiMap.isEmpty())
        {
            return typeName;
        }

        String result = classNameBiMap.containsKey(typeName) ? classNameBiMap.get(typeName) : typeName;
        return result;
    }

    public String unmap(String typeName)
    {
        if (classNameBiMap == null)
        {
            return typeName;
        }
        return classNameBiMap.containsValue(typeName) ? classNameBiMap.inverse().get(typeName) : typeName;
    }


    @Override
    public String mapMethodName(String owner, String name, String desc)
    {
        if (classNameBiMap==null || classNameBiMap.isEmpty())
        {
            return name;
        }
        Map<String, String> methodMap = getMethodMap(owner);
        String methodDescriptor = name+desc;
        return methodMap!=null && methodMap.containsKey(methodDescriptor) ? methodMap.get(methodDescriptor) : name;
    }

    private Map<String,String> getFieldMap(String className)
    {
        if (!fieldNameMaps.containsKey(className))
        {
            findAndMergeSuperMaps(className);
        }
        return fieldNameMaps.get(className);
    }

    private Map<String,String> getMethodMap(String className)
    {
        if (!methodNameMaps.containsKey(className))
        {
            findAndMergeSuperMaps(className);
        }
        return methodNameMaps.get(className);
    }

    private void findAndMergeSuperMaps(String name)
    {
        try
        {
            byte[] classBytes = classLoader.getClassBytes(name);
            if (classBytes == null)
            {
                return;
            }
            ClassReader cr = new ClassReader(classBytes);
            String superName = cr.getSuperName();
            String[] interfaces = cr.getInterfaces();
            if (interfaces == null)
            {
                interfaces = new String[0];
            }
            mergeSuperMaps(name, superName, interfaces);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
    public void mergeSuperMaps(String name, String superName, String[] interfaces)
    {
        if (classNameBiMap == null || classNameBiMap.isEmpty())
        {
            return;
        }
        List<String> allParents = ImmutableList.<String>builder().add(superName).addAll(Arrays.asList(interfaces)).build();
        for (String parentThing : allParents)
        {
            if (superName != null && classNameBiMap.containsKey(superName) && !methodNameMaps.containsKey(superName))
            {
                findAndMergeSuperMaps(superName);
            }
        }
        Map<String, String> methodMap = Maps.<String,String>newHashMap();
        Map<String, String> fieldMap = Maps.<String,String>newHashMap();
        for (String parentThing : allParents)
        {
            if (methodNameMaps.containsKey(parentThing))
            {
                methodMap.putAll(methodNameMaps.get(parentThing));
            }
            if (fieldNameMaps.containsKey(parentThing))
            {
                fieldMap.putAll(fieldNameMaps.get(parentThing));
            }
        }
        if (rawMethodMaps.containsKey(name))
        {
            methodMap.putAll(rawMethodMaps.get(name));
        }
        if (rawFieldMaps.containsKey(name))
        {
            fieldMap.putAll(rawFieldMaps.get(name));
        }
        methodNameMaps.put(name, ImmutableMap.copyOf(methodMap));
        fieldNameMaps.put(name, ImmutableMap.copyOf(fieldMap));
    }
}
