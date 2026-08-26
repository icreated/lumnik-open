package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.api.writers.BulkItemWriter;
import io.lumnik.hub.connector.api.writers.CopyItemWriter;
import io.lumnik.hub.connector.api.writers.JpaItemWriter;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

class WriterTiersTest {
  @Test
  void jpa_writer_profile_is_safe() {
    var w = new JpaItemWriter<String>() {
      @Override
      protected Object toEntity(String item, ConnectorContext ctx) {
        return item;
      }
    };
    assertThat(w.profile()).isInstanceOf(WritePerformanceProfile.Safe.class);
  }

  @Test
  void bulk_writer_profile_is_bulk() {
    var w = new BulkItemWriter<String>() {
      @Override
      protected String insertSql() {
        return "INSERT INTO test (col) VALUES (?)";
      }

      @Override
      protected void bindRow(PreparedStatement ps, String item) {
      }
    };
    assertThat(w.profile()).isInstanceOf(WritePerformanceProfile.Bulk.class);
  }

  @Test
  void copy_writer_profile_is_bulkload() {
    var w = new CopyItemWriter<String>() {
      @Override
      protected String copyCommand() {
        return "COPY test (col) FROM STDIN";
      }

      @Override
      protected String toCsvRow(String item) {
        return item;
      }
    };
    assertThat(w.profile()).isInstanceOf(WritePerformanceProfile.BulkLoad.class);
  }
}
