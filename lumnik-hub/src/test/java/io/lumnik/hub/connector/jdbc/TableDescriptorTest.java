package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TableDescriptorTest {

  private final ObjectMapper M = new ObjectMapper().registerModule(new Jdk8Module());

  @Test
  void column_descriptor_round_trip_json() throws Exception {
    ColumnDescriptor c = new ColumnDescriptor("name", "VARCHAR", 255, true);
    String json = M.writeValueAsString(c);
    ColumnDescriptor decoded = M.readValue(json, ColumnDescriptor.class);
    assertEquals(c, decoded);
  }

  @Test
  void fk_descriptor_round_trip_json() throws Exception {
    FkDescriptor fk = new FkDescriptor("c_bp_group_id", "public", "c_bp_group", "c_bp_group_id");
    String json = M.writeValueAsString(fk);
    FkDescriptor decoded = M.readValue(json, FkDescriptor.class);
    assertEquals(fk, decoded);
  }

  @Test
  void table_descriptor_round_trip_json() throws Exception {
    TableDescriptor t = new TableDescriptor(
        "public", "c_bpartner",
        List.of("c_bpartner_id"),
        List.of(new ColumnDescriptor("c_bpartner_id", "BIGINT", 19, false),
                new ColumnDescriptor("name", "VARCHAR", 255, true)),
        List.of(new FkDescriptor("c_bp_group_id", "public", "c_bp_group", "c_bp_group_id")),
        Optional.of("updated"),
        8421L);
    String json = M.writeValueAsString(t);
    TableDescriptor decoded = M.readValue(json, TableDescriptor.class);
    assertEquals(t, decoded);
  }

  @Test
  void table_descriptor_with_no_watermark_serializes() throws Exception {
    TableDescriptor t = new TableDescriptor(
        "public", "ref_lookup",
        List.of("id"),
        List.of(new ColumnDescriptor("id", "INTEGER", 10, false)),
        List.of(),
        Optional.empty(),
        12L);
    String json = M.writeValueAsString(t);
    assertNotNull(json);
    TableDescriptor decoded = M.readValue(json, TableDescriptor.class);
    assertEquals(Optional.empty(), decoded.suggestedWatermark());
  }
}
