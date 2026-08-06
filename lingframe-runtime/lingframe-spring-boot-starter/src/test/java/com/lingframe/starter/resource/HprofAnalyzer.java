package com.lingframe.starter.resource;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public class HprofAnalyzer {

    public static void main(String[] args) throws Exception {
        File hprofFile = null;
        File tempDir = new File("C:/Users/Knight/AppData/Local/Temp");
        File[] files = tempDir.listFiles((dir, name) -> name.startsWith("ling-leak-") && name.endsWith(".hprof"));
        if (files != null && files.length > 0) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            hprofFile = files[0];
        }
        if (hprofFile == null || !hprofFile.exists()) {
            System.err.println("No hprof file found in " + tempDir);
            return;
        }

        System.out.println("Analyzing hprof: " + hprofFile.getAbsolutePath() + " (" + hprofFile.length() + " bytes)");

        Map<Long, String> strings = new HashMap<>();
        Map<Long, Long> classObjToNameId = new HashMap<>();
        Map<Long, String> classNames = new HashMap<>();
        Map<Long, ClassInfo> classes = new HashMap<>();
        Map<Long, Long> instanceToClass = new HashMap<>();
        Map<Long, List<Long>> objectReferences = new HashMap<>();
        Set<Long> gcRoots = new HashSet<>();
        Map<Long, String> rootDescriptions = new HashMap<>();

        try (FileInputStream fis = new FileInputStream(hprofFile);
             DataInputStream in = new DataInputStream(new BufferedInputStream(fis, 1024 * 1024))) {

            StringBuilder magic = new StringBuilder();
            byte b;
            while ((b = in.readByte()) != 0) {
                magic.append((char) b);
            }
            int idSize = in.readInt();
            long timestamp = in.readLong();
            System.out.println("Hprof header: magic=" + magic + ", idSize=" + idSize);

            while (in.available() > 0) {
                int tag = in.readUnsignedByte();
                int time = in.readInt();
                long length = in.readInt() & 0xFFFFFFFFL;

                if (tag == 0x01) { // STRING
                    long id = readId(in, idSize);
                    byte[] data = new byte[(int) (length - idSize)];
                    in.readFully(data);
                    strings.put(id, new String(data, "UTF-8"));
                } else if (tag == 0x02) { // LOAD CLASS
                    int classSerial = in.readInt();
                    long classObjId = readId(in, idSize);
                    int stackTrace = in.readInt();
                    long nameId = readId(in, idSize);
                    classObjToNameId.put(classObjId, nameId);
                } else if (tag == 0x0C || tag == 0x1C) { // HEAP DUMP
                    long bytesRead = 0;
                    while (bytesRead < length) {
                        int subTag = in.readUnsignedByte();
                        bytesRead += 1;

                        switch (subTag) {
                            case 0xFF: // ROOT UNKNOWN
                            case 0x01: // ROOT JNI GLOBAL
                            case 0x02: // ROOT JNI LOCAL
                            case 0x03: // ROOT JAVA FRAME
                            case 0x04: // ROOT NATIVE STACK
                            case 0x05: // ROOT STICKY CLASS
                            case 0x06: // ROOT THREAD BLOCK
                            case 0x07: // ROOT MONITOR USED
                            case 0x08: // ROOT THREAD OBJ
                            case 0x89: // ROOT INTERNED STRING
                            case 0x8A: // ROOT FINALIZING
                            case 0x8B: // ROOT UNREACHABLE
                            case 0x8C: // ROOT DEBUG INFO
                            case 0x8D: // ROOT CORE THREAD
                            {
                                long id = readId(in, idSize);
                                bytesRead += idSize;
                                gcRoots.add(id);
                                rootDescriptions.put(id, "GC_ROOT_0x" + Integer.toHexString(subTag));
                                if (subTag == 0x01) { readId(in, idSize); bytesRead += idSize; }
                                else if (subTag == 0x02 || subTag == 0x03 || subTag == 0x06 || subTag == 0x08 || subTag == 0x8C || subTag == 0x8D) {
                                    in.readInt(); bytesRead += 4;
                                    if (subTag == 0x03) { in.readInt(); bytesRead += 4; }
                                    if (subTag == 0x08) { in.readInt(); bytesRead += 4; }
                                }
                                else if (subTag == 0x04) { in.readInt(); bytesRead += 4; }
                                break;
                            }
                            case 0x20: // CLASS DUMP
                            {
                                long classObjId = readId(in, idSize);
                                in.readInt(); // stack trace
                                long superClassId = readId(in, idSize);
                                long loaderId = readId(in, idSize);
                                long signers = readId(in, idSize);
                                long domain = readId(in, idSize);
                                readId(in, idSize);
                                readId(in, idSize);
                                int instSize = in.readInt();
                                bytesRead += idSize * 7 + 8;

                                if (loaderId != 0) {
                                    objectReferences.computeIfAbsent(classObjId, k -> new ArrayList<>()).add(loaderId);
                                }

                                int cpCount = in.readUnsignedShort();
                                bytesRead += 2;
                                for (int i = 0; i < cpCount; i++) {
                                    in.readUnsignedShort();
                                    byte type = in.readByte();
                                    int sz = skipTypeValue(in, type, idSize);
                                    bytesRead += 3 + sz;
                                }

                                int statCount = in.readUnsignedShort();
                                bytesRead += 2;
                                for (int i = 0; i < statCount; i++) {
                                    long nameId = readId(in, idSize);
                                    byte type = in.readByte();
                                    bytesRead += idSize + 1;
                                    if (type == 2) {
                                        long refId = readId(in, idSize);
                                        bytesRead += idSize;
                                        if (refId != 0) {
                                            objectReferences.computeIfAbsent(classObjId, k -> new ArrayList<>()).add(refId);
                                        }
                                    } else {
                                        int sz = skipValue(in, type, idSize);
                                        bytesRead += sz;
                                    }
                                }

                                int fieldCount = in.readUnsignedShort();
                                bytesRead += 2;
                                List<FieldInfo> instanceFields = new ArrayList<>();
                                for (int i = 0; i < fieldCount; i++) {
                                    long nameId = readId(in, idSize);
                                    byte type = in.readByte();
                                    bytesRead += idSize + 1;
                                    instanceFields.add(new FieldInfo(nameId, type));
                                }

                                ClassInfo ci = new ClassInfo(classObjId, superClassId, instanceFields);
                                classes.put(classObjId, ci);
                                break;
                            }
                            case 0x21: // INSTANCE DUMP
                            {
                                long instId = readId(in, idSize);
                                in.readInt(); // stack trace
                                long classObjId = readId(in, idSize);
                                int bytesFollow = in.readInt();
                                bytesRead += idSize * 2 + 8 + bytesFollow;

                                instanceToClass.put(instId, classObjId);

                                byte[] instBytes = new byte[bytesFollow];
                                in.readFully(instBytes);
                                parseInstanceReferences(instId, classObjId, instBytes, classes, objectReferences, idSize);
                                break;
                            }
                            case 0x22: // OBJECT ARRAY DUMP
                            {
                                long arrayId = readId(in, idSize);
                                in.readInt();
                                int numElements = in.readInt();
                                long elemClassObjId = readId(in, idSize);
                                bytesRead += idSize * 2 + 8 + (long) numElements * idSize;

                                instanceToClass.put(arrayId, elemClassObjId);
                                for (int i = 0; i < numElements; i++) {
                                    long refId = readId(in, idSize);
                                    if (refId != 0) {
                                        objectReferences.computeIfAbsent(arrayId, k -> new ArrayList<>()).add(refId);
                                    }
                                }
                                break;
                            }
                            case 0x23: // PRIMITIVE ARRAY DUMP
                            {
                                long arrayId = readId(in, idSize);
                                in.readInt();
                                int numElements = in.readInt();
                                byte elemType = in.readByte();
                                int elemSize = getPrimitiveTypeSize(elemType);
                                long skipBytes = (long) numElements * elemSize;
                                in.skipBytes((int) skipBytes);
                                bytesRead += idSize + 9 + skipBytes;
                                break;
                            }
                            default:
                                System.err.println("Unknown subTag 0x" + Integer.toHexString(subTag) + " at bytesRead=" + bytesRead + " / " + length);
                                return;
                        }
                    }
                } else {
                    in.skipBytes((int) length);
                }
            }

            // Resolve class names
            for (Map.Entry<Long, Long> entry : classObjToNameId.entrySet()) {
                String name = strings.get(entry.getValue());
                if (name != null) {
                    classNames.put(entry.getKey(), name);
                }
            }

            Set<Long> lingClassLoaderIds = new HashSet<>();
            for (Map.Entry<Long, Long> entry : instanceToClass.entrySet()) {
                long instId = entry.getKey();
                long classObjId = entry.getValue();
                String cname = classNames.get(classObjId);
                if (cname != null && cname.contains("LingClassLoader")) {
                    lingClassLoaderIds.add(instId);
                    System.out.println("Found LingClassLoader instance! ID=0x" + Long.toHexString(instId));
                }
            }

            System.out.println("Found " + lingClassLoaderIds.size() + " LingClassLoader instances.");
            System.out.println("Total instances indexed: " + instanceToClass.size() + ", total reference nodes: " + objectReferences.size());

            // Reverse reference graph: dstId -> List<srcId>
            Map<Long, List<Long>> incomingRefs = new HashMap<>();
            for (Map.Entry<Long, List<Long>> entry : objectReferences.entrySet()) {
                long src = entry.getKey();
                for (long dst : entry.getValue()) {
                    incomingRefs.computeIfAbsent(dst, k -> new ArrayList<>()).add(src);
                }
            }

            // Trace shortest paths from target LingClassLoader to GC roots
            for (long targetId : lingClassLoaderIds) {
                System.out.println("\n=======================================================");
                System.out.println("Tracing GC Root paths for LingClassLoader 0x" + Long.toHexString(targetId));
                System.out.println("=======================================================");
                traceShortestPathToGCRoot(targetId, incomingRefs, gcRoots, instanceToClass, classNames, rootDescriptions);
            }
        }
    }

    private static long readId(DataInputStream in, int idSize) throws IOException {
        if (idSize == 8) return in.readLong();
        return in.readInt() & 0xFFFFFFFFL;
    }

    private static int skipTypeValue(DataInputStream in, byte type, int idSize) throws IOException {
        if (type == 2) { readId(in, idSize); return idSize; }
        return skipValue(in, type, idSize);
    }

    private static int skipValue(DataInputStream in, byte type, int idSize) throws IOException {
        int sz = getPrimitiveTypeSize(type);
        in.skipBytes(sz);
        return sz;
    }

    private static int getPrimitiveTypeSize(byte type) {
        switch (type) {
            case 4: return 1;
            case 5: return 2;
            case 6: return 4;
            case 7: return 8;
            case 8: return 1;
            case 9: return 2;
            case 10: return 4;
            case 11: return 8;
            default: return 0;
        }
    }

    private static void parseInstanceReferences(long instId, long classObjId, byte[] bytes,
                                                Map<Long, ClassInfo> classes,
                                                Map<Long, List<Long>> objectReferences,
                                                int idSize) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long currentClass = classObjId;
        while (currentClass != 0) {
            ClassInfo ci = classes.get(currentClass);
            if (ci == null) break;
            for (FieldInfo fi : ci.fields) {
                if (fi.type == 2) { // OBJECT
                    if (buf.remaining() >= idSize) {
                        long refId = (idSize == 8) ? buf.getLong() : (buf.getInt() & 0xFFFFFFFFL);
                        if (refId != 0) {
                            objectReferences.computeIfAbsent(instId, k -> new ArrayList<>()).add(refId);
                        }
                    }
                } else {
                    int sz = getPrimitiveTypeSize(fi.type);
                    if (buf.remaining() >= sz) {
                        buf.position(buf.position() + sz);
                    }
                }
            }
            currentClass = ci.superClassId;
        }
    }

    private static void traceShortestPathToGCRoot(long targetId, Map<Long, List<Long>> incomingRefs,
                                                 Set<Long> gcRoots, Map<Long, Long> instanceToClass,
                                                 Map<Long, String> classNames, Map<Long, String> rootDescriptions) {
        Queue<Long> queue = new LinkedList<>();
        Map<Long, Long> parentMap = new HashMap<>();
        Set<Long> visited = new HashSet<>();

        queue.add(targetId);
        visited.add(targetId);

        long foundRoot = 0;
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (gcRoots.contains(current)) {
                foundRoot = current;
                break;
            }

            List<Long> srcs = incomingRefs.get(current);
            if (srcs != null) {
                for (long src : srcs) {
                    if (visited.add(src)) {
                        parentMap.put(src, current);
                        queue.add(src);
                    }
                }
            }
        }

        if (foundRoot == 0) {
            System.out.println("No path to GC root found!");
            return;
        }

        System.out.println("Path to GC Root found!");
        List<Long> path = new ArrayList<>();
        long curr = foundRoot;
        while (curr != targetId) {
            path.add(curr);
            curr = parentMap.get(curr);
        }
        path.add(targetId);

        for (int i = 0; i < path.size(); i++) {
            long id = path.get(i);
            Long classId = instanceToClass.get(id);
            String cname = classId != null ? classNames.get(classId) : (classNames.get(id) != null ? "Class<" + classNames.get(id) + ">" : "Unknown");
            String desc = rootDescriptions.get(id);
            System.out.printf("[%2d] 0x%016x (%s) %s\n", i, id, cname, desc != null ? "<- " + desc : "");
        }
    }

    static class ClassInfo {
        long classObjId;
        long superClassId;
        List<FieldInfo> fields;

        ClassInfo(long classObjId, long superClassId, List<FieldInfo> fields) {
            this.classObjId = classObjId;
            this.superClassId = superClassId;
            this.fields = fields;
        }
    }

    static class FieldInfo {
        long nameId;
        byte type;

        FieldInfo(long nameId, byte type) {
            this.nameId = nameId;
            this.type = type;
        }
    }
}
