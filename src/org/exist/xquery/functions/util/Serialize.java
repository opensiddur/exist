/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-04 The eXist Project
 *  http://exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.xquery.functions.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Properties;

import javax.xml.transform.OutputKeys;

import org.exist.dom.QName;
import org.exist.storage.serializers.Serializer;
import org.exist.util.serializer.SAXSerializer;
import org.exist.util.serializer.SerializerPool;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.Option;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.xml.sax.SAXException;

public class Serialize extends BasicFunction {

    public final static FunctionSignature signatures[] = {
        new FunctionSignature(
            new QName("serialize", UtilModule.NAMESPACE_URI, UtilModule.PREFIX),
            "DEPRECATED.  Use the file:serialize() function in the file extension module instead! " +
            "Writes the node set passed in parameter $a into a file on the file system. The " +
            "full path to the file is specified in parameter $b. $c contains a " +
            "sequence of zero or more serialization parameters specified as key=value pairs. The " +
            "serialization options are the same as those recognized by \"declare option exist:serialize\". " +
            "The function does NOT automatically inherit the serialization options of the XQuery it is " +
            "called from. False is returned if the " +
            "specified file can not be created or is not writable, true on success. The empty " +
            "sequence is returned if the argument sequence is empty.",
            new SequenceType[] { 
                new SequenceType(Type.NODE, Cardinality.ZERO_OR_MORE),
                new SequenceType(Type.STRING, Cardinality.EXACTLY_ONE),
                new SequenceType(Type.STRING, Cardinality.ZERO_OR_MORE)
            },
            new SequenceType(Type.BOOLEAN, Cardinality.ZERO_OR_ONE)
        ),
        new FunctionSignature(
                new QName("serialize", UtilModule.NAMESPACE_URI, UtilModule.PREFIX),
                "Returns the Serialized node set passed in parameter $a. $b contains a " +
                "sequence of zero or more serialization parameters specified as key=value pairs. The " +
                "serialization options are the same as those recognized by \"declare option exist:serialize\". " +
                "The function does NOT automatically inherit the serialization options of the XQuery it is " +
                "called from.",
                new SequenceType[] { 
                    new SequenceType(Type.NODE, Cardinality.ZERO_OR_MORE),
                    new SequenceType(Type.STRING, Cardinality.ZERO_OR_MORE)
                },
                new SequenceType(Type.STRING, Cardinality.ZERO_OR_ONE)
        )
    };
        
    
    public Serialize(XQueryContext context, FunctionSignature signature)
    {
        super(context, signature);
    }

    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException
    {
        if(args[0].isEmpty())
            return Sequence.EMPTY_SEQUENCE;
        
        
        Properties outputProperties = null;
        OutputStream os = null;
        
        if(args.length == 3)
        {
        	/** serialize to disk **/
        	
	        // check the file output path
	        String path = args[1].itemAt(0).getStringValue();
	        File file = new File(path);
	        if (file.isDirectory()) {
	            LOG.debug("Output file is a directory: " + file.getAbsolutePath());
	            return BooleanValue.FALSE;
	        }
	        if (file.exists() && !file.canWrite()) {
	            LOG.debug("Cannot write to file " + file.getAbsolutePath());
	            return BooleanValue.FALSE;
	        }
	        
	        //parse serialization options from third argument to function
	        outputProperties = parseSerializationOptions(args[2].iterate());
	        
	        //setup output stream for file
	        try
	        {
	        	os = new FileOutputStream(file);
	        }
	        catch(IOException e)
	        {
	        	throw new XPathException(getASTNode(), "A problem ocurred while serializing the node set: " + e.getMessage(), e);
	        }
	        
	        //do the serialization
	        serialize(args[0].iterate(), outputProperties, os);
	    
	        return BooleanValue.TRUE;
        }
        else
        {
        	/** serialize to string **/

	        //parse serialization options from second argument to function        	
        	outputProperties = parseSerializationOptions(args[1].iterate());
        	
        	//setup output stream for byte array
        	os = new ByteArrayOutputStream();

	        //do the serialization
        	serialize(args[0].iterate(), outputProperties, os);
        	
        	try
        	{
        		String encoding = outputProperties.getProperty(OutputKeys.ENCODING, "UTF-8");
        		return new StringValue(new String(((ByteArrayOutputStream)os).toByteArray(), encoding));
        	}
        	catch(UnsupportedEncodingException e)
        	{
        		throw new XPathException(getASTNode(), "A problem ocurred while serializing the node set: " + e.getMessage(), e);
        	}
        }
        
    }
    
    private Properties parseSerializationOptions(SequenceIterator siSerializeParams) throws XPathException
    {
    	//parse serialization options
        Properties outputProperties = new Properties();
        outputProperties.setProperty(OutputKeys.INDENT, "yes");
        outputProperties.setProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        while(siSerializeParams.hasNext())
        {
            String serializeParams = siSerializeParams.nextItem().getStringValue();
            String params[] = serializeParams.split(" ");
            for(String param : params)
            {
                String opt[] = Option.parseKeyValuePair(param);
                outputProperties.setProperty(opt[0], opt[1]);
            }
        }

        return outputProperties;
    }
    
    private void serialize(SequenceIterator siNode, Properties outputProperties, OutputStream os) throws XPathException
    {
        // serialize the node set
        SAXSerializer sax = (SAXSerializer) SerializerPool.getInstance().borrowObject(SAXSerializer.class);
        try
        {
            String encoding = outputProperties.getProperty(OutputKeys.ENCODING, "UTF-8");
            Writer writer = new OutputStreamWriter(os, encoding);
            sax.setOutput(writer, outputProperties);
            Serializer serializer = context.getBroker().getSerializer();
            serializer.reset();
            serializer.setProperties(outputProperties);
            serializer.setSAXHandlers(sax, sax);

            sax.startDocument();
            
            while(siNode.hasNext())
            {
        	   NodeValue next = (NodeValue)siNode.nextItem();
               serializer.toSAX(next);	
            }
            
            sax.endDocument();
            writer.close();
        }
        catch(SAXException e)
        {
            throw new XPathException(getASTNode(), "A problem ocurred while serializing the node set: " + e.getMessage(), e);
        }
        catch (IOException e)
        {
            throw new XPathException(getASTNode(), "A problem ocurred while serializing the node set: " + e.getMessage(), e);
        }
        finally
        {
            SerializerPool.getInstance().returnObject(sax);
        }
    }
}
