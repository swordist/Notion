package edu.mayo.qia.pacs.ctp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.dcm4che.dict.Tags;
import org.dcm4che2.data.DicomElement;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.ElementDictionary;
import org.dcm4che2.data.SpecificCharacterSet;
import org.dcm4che2.data.Tag;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.DicomOutputStream;
import org.dcm4che2.util.TagUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptableObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.util.DigestUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import edu.mayo.qia.pacs.PACS;
import edu.mayo.qia.pacs.components.Pool;
import edu.mayo.qia.pacs.components.PoolContainer;
import edu.mayo.qia.pacs.dicom.TagLoader;

@Component
@Scope("prototype")
/** Helper class containing some functions to use for the Anonymizer */
public class Anonymizer {
  static Logger logger = Logger.getLogger(Anonymizer.class);
  private Pool pool;

  @Autowired
  JdbcTemplate template;

  @Autowired
  TransactionTemplate transactionTemplate;

  public void setPool(Pool pool) {
    this.pool = pool;
  }

  public ScriptableObject setBindings(ScriptableObject scope, DicomObject tags) {
    ScriptableObject.putProperty(scope, "pool", pool);
    ScriptableObject.putProperty(scope, "anon", this);
    ScriptableObject.putProperty(scope, "anonymizer", this);
    NativeObject tagObject = new NativeObject();
    Iterator<DicomElement> iterator = tags.datasetIterator();
    while (iterator.hasNext()) {
      DicomElement element = iterator.next();
      String tagName = ElementDictionary.getDictionary().nameOf(element.tag());
      tagName = tagName.replaceAll("[ ']+", "");
      tagObject.defineProperty(tagName, tags.getString(element.tag()), NativeObject.READONLY);
      // logger.info("Setting: " + tagName + ": " +
      // tags.getString(element.tag()));
    }
    ScriptableObject.putProperty(scope, "tags", tagObject);
    return scope;
  }

  public String hash(String value) {
    return hash(value, value.length());
  }

  public String lookup(String type, String name) {
    Object[] v = lookupValueAndKey(type, name);
    return (String) v[0];
  }

  public Object[] lookupValueAndKey(String type, String name) {
    final Object[] out = new Object[] { null, null };
    template.query("select Value, LookupKey from LOOKUP where PoolKey = ? and Type = ? and Name = ?", new Object[] { pool.poolKey, type, name }, new RowCallbackHandler() {

      @Override
      public void processRow(ResultSet rs) throws SQLException {
        out[0] = rs.getString("Value");
        out[1] = rs.getInt("LookupKey");
      }
    });
    return out;
  }

  public Integer setValue(final String type, final String name, final String value) {
    return transactionTemplate.execute(new TransactionCallback<Integer>() {

      @Override
      public Integer doInTransaction(TransactionStatus status) {
        Object[] k = lookupValueAndKey(type, name);
        if (k.length == 1) {
          // Update it
          template.update("update LOOKUP set Value = ? where LookupKey = ?", value, k[1]);
          return (Integer) k[1];
        } else {
          KeyHolder keyHolder = new GeneratedKeyHolder();
          template.update(new PreparedStatementCreator() {

            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
              PreparedStatement statement = con.prepareStatement("insert into LOOKUP ( PoolKey, Type, Name, Value ) VALUES ( ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
              statement.setInt(1, pool.poolKey);
              statement.setString(2, type);
              statement.setString(3, name);
              statement.setString(4, value);
              return statement;
            }
          }, keyHolder);
          return keyHolder.getKey().intValue();
        }

      }
    });
  }

  String getSequence(final String type) {
    final String internalType = "Sequence." + type;
    return transactionTemplate.execute(new TransactionCallback<String>() {

      @Override
      public String doInTransaction(TransactionStatus status) {
        Object[] k = lookupValueAndKey("Pool", internalType);
        String sequenceName = null;
        if (k[0] == null) {
          // Create an empty, grab a sequence from the POOL UID
          Integer i = template.queryForObject("VALUES( NEXT VALUE FOR UID" + pool.poolKey + ")", Integer.class);
          sequenceName = "poolsequence" + i;
          template.update("create sequence " + sequenceName + " AS INT START WITH 1");
          setValue("Pool", internalType, sequenceName);
        } else {
          sequenceName = (String) k[0];
        }
        return sequenceName;
      }
    });
  }

  public int sequenceNumber(final String type, final String name) {
    return transactionTemplate.execute(new TransactionCallback<Integer>() {

      @Override
      public Integer doInTransaction(TransactionStatus status) {
        String internalType = "Sequence." + type;
        String v = lookup(internalType, name);
        if (v != null) {
          return Integer.decode(v);
        } else {
          String sequence = getSequence(internalType);
          Integer i = template.queryForObject("VALUES( NEXT VALUE FOR " + sequence + ")", Integer.class);
          setValue(internalType, name, i.toString());
          return i;
        }
      }
    });
  }

  public String hash(String value, int length) {
    String result = value;
    try {
      result = DigestUtil.getUSMD5(value);
    } catch (Exception e) {
      logger.error("Failed to compute MD5 hash", e);
    }
    return result.substring(0, length);
  }

  public static FileObject process(PoolContainer poolContainer, FileObject fileObject, File original) {
    JdbcTemplate template = PACS.context.getBean("template", JdbcTemplate.class);

    try {
      // Load the tags, replace PatientName, PatientID and AccessionNumber
      DicomInputStream dis = new DicomInputStream(fileObject.getFile());
      final DicomObject dcm = dis.readDicomObject();
      dis.close();
      DicomObject originalTags = TagLoader.loadTags(original);

      Anonymizer function = PACS.context.getBean("anonymizer", Anonymizer.class);
      function.setPool(poolContainer.getPool());

      final Context context = Context.enter();
      try {
        final ScriptableObject scope = function.setBindings(context.initStandardObjects(), originalTags);

        // Run through all the stored bindings
        template.query("select Tag, Script from SCRIPT where PoolKey = ?", new Object[] { poolContainer.getPool().poolKey }, new RowCallbackHandler() {

          @Override
          public void processRow(ResultSet rs) throws SQLException {
            String tag = rs.getString("Tag");
            int tagValue = Tag.toTag(tag);
            String script = rs.getString("Script");
            logger.info("Processing tag: " + tag + " with script: " + script);
            Object result = dcm.getString(tagValue);
            try {
              result = context.evaluateString(scope, script, "inline", 1, null);
            } catch (Exception e) {
              logger.error("Failed to process the script correctly: " + script, e);
            }
            if (result instanceof String) {
              dcm.putString(tagValue, dcm.get(tagValue).vr(), (String) result);
            } else {
              logger.error("Expected a string back from script, but instead got: " + result.toString());
            }

          }
        });

      } finally {
        Context.exit();
      }
      File output = new File(fileObject.getFile().getParentFile(), UUID.randomUUID().toString());
      FileOutputStream fos = new FileOutputStream(output);
      BufferedOutputStream bos = new BufferedOutputStream(fos);
      DicomOutputStream dos = new DicomOutputStream(bos);
      dos.writeDicomFile(dcm);
      dos.close();

      fileObject.getFile().delete();
      FileUtils.moveFile(output, fileObject.getFile());
    } catch (Exception e) {
      logger.error("Error changing patient info", e);
    }
    return fileObject;
  }
}
