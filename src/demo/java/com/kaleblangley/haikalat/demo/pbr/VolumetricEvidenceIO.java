package com.kaleblangley.haikalat.demo.pbr;

import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Shared structured evidence writer; no renderer objects or native handles are serialized. */
final class VolumetricEvidenceIO {
    private VolumetricEvidenceIO() { }
    static void save(Path path,Object values) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try(JsonGenerator json=new JsonFactory().createGenerator(path.toFile(),JsonEncoding.UTF8)) {
            json.useDefaultPrettyPrinter();write(json,values);
        }
    }
    private static void write(JsonGenerator json,Object value) throws IOException {
        if(value==null)json.writeNull();
        else if(value instanceof Map<?,?> map) {
            json.writeStartObject();for(var entry:map.entrySet()){json.writeFieldName(entry.getKey().toString());write(json,entry.getValue());}json.writeEndObject();
        } else if(value instanceof Iterable<?> list) {
            json.writeStartArray();for(Object item:list)write(json,item);json.writeEndArray();
        } else if(value.getClass().isArray()) {
            json.writeStartArray();for(int i=0;i<Array.getLength(value);i++)write(json,Array.get(value,i));json.writeEndArray();
        } else if(value.getClass().isRecord()) {
            json.writeStartObject();for(RecordComponent field:value.getClass().getRecordComponents()) {
                json.writeFieldName(field.getName());try{
                    var accessor=field.getAccessor();if(!accessor.canAccess(value))accessor.trySetAccessible();
                    write(json,accessor.invoke(value));}
                catch(ReflectiveOperationException failure){throw new IOException("cannot serialize evidence field "+field.getName(),failure);}
            }json.writeEndObject();
        } else if(value instanceof Number number) {
            if(!Double.isFinite(number.doubleValue()))throw new IOException("nonfinite evidence number");json.writeNumber(number.toString());
        } else if(value instanceof Boolean b)json.writeBoolean(b);
        else json.writeString(value.toString());
    }
}
