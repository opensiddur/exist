/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2001-2018 The eXist Project
 * http://exist-db.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.storage;

import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.IndexInfo;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.journal.Journal;
import org.exist.storage.txn.Txn;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.FileInputSource;
import org.exist.util.LockException;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import javax.xml.transform.Source;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * @author <a href="mailto:adam@evolvedbinary.com">Adam Retter</a>
 */
public class RecoverXmlTest extends AbstractRecoverTest {

    @Test
    public void storeLargeAndLoad() throws LockException, TriggerException, PermissionDeniedException, EXistException,
            IOException, DatabaseConfigurationException {
        // generate a string filled with random a-z characters which is larger than the journal buffer
        final byte[] buf = new byte[Journal.BUFFER_SIZE * 3]; // 3 * the journal buffer size
        final Random random = new Random();
        for (int i = 0; i < buf.length; i++) {
            final byte singleByteChar = (byte)('a' + random.nextInt('z' - 'a' - 1));
            buf[i] = singleByteChar;
        }
        final String largeText = new String(buf, UTF_8);
        final String xml = "<large-text>" + largeText + "</large-text>";
        final InputSource source = new StringInputSource(xml);
        source.setEncoding("UTF-8");

        BrokerPool.FORCE_CORRUPTION = true;
        store(COMMIT, source, "large.xml");
        flushJournal();

        existEmbeddedServer.restart();

        BrokerPool.FORCE_CORRUPTION = false;
        read(MUST_EXIST, source, "large.xml");
    }

    @Override
    protected Path getTestFile1() throws IOException {
        return resolveTestFile("conf.xml");
    }

    @Override
    protected Path getTestFile2() throws IOException {
        return resolveTestFile("log4j2.xml");
    }

    @Override
    protected void storeAndVerify(final DBBroker broker, final Txn transaction, final Collection collection,
            final InputSource data, final String dbFilename) throws EXistException, PermissionDeniedException,
            IOException, LockException {
        final XmldbURI docUri = XmldbURI.create(dbFilename);
        try {
            final IndexInfo indexInfo =
                    collection.validateXMLResource(transaction, broker, docUri, data);

            collection.store(transaction, broker, indexInfo, data);

        } catch (final SAXException e) {
            throw new IOException(e);
        }


        final DocumentImpl doc = broker.getResource(collection.getURI().append(docUri), Permission.READ);
        assertNotNull(doc);

        final Source expected;
        if (data instanceof FileInputSource) {
            expected = Input.fromFile(((FileInputSource)data).getFile().toFile()).build();
        } else if(data instanceof StringInputSource) {
            try (final Reader reader = data.getCharacterStream()) {
                expected = Input.fromString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + readAll(reader)).build();
            }
        } else {
            throw new IllegalStateException();
        }
        final Source actual = Input.fromDocument(doc).build();

        final Diff diff = DiffBuilder.compare(expected).withTest(actual)
                .checkForIdentical()
                .build();

        assertFalse("XML identical: " + diff.toString(), diff.hasDifferences());
    }

    private final String readAll(final Reader reader) throws IOException {
        final StringBuilder builder = new StringBuilder();
        final char buf[] = new char[4096];
        int read = -1;
        while ((read = reader.read(buf)) > -1) {
            builder.append(buf, 0, read);
        }
        return builder.toString();
    }

    @Override
    protected void readAndVerify(final DBBroker broker, final DocumentImpl doc, final InputSource data,
            final String dbFilename) throws IOException {

        final Source expected;
        if (data instanceof FileInputSource) {
            expected = Input.fromFile(((FileInputSource)data).getFile().toFile()).build();
        } else if(data instanceof StringInputSource) {
            try (final Reader reader = data.getCharacterStream()) {
                expected = Input.fromString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + readAll(reader)).build();
            }
        } else {
            throw new IllegalStateException();
        }

        final Source actual = Input.fromDocument(doc).build();

        final Diff diff = DiffBuilder.compare(expected).withTest(actual)
                .checkForIdentical()
                .build();

        assertFalse("XML identical: " + diff.toString(), diff.hasDifferences());
    }
}
