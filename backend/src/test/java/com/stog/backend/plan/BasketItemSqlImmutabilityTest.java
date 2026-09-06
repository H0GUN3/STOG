package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class BasketItemSqlImmutabilityTest {
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void everyFirstWritePayloadAndProvenanceFieldIsSqlImmutableButStatusCanAdvance() {
        String marker = "task9-sql-" + UUID.randomUUID();
        List<Long> ids = tx().execute(status -> {
            long user = jdbc.sql("INSERT INTO users(nickname) VALUES (:n) RETURNING id").param("n", marker).query(Long.class).single();
            long trip = jdbc.sql("INSERT INTO trips(owner_id,title,activity_type) VALUES (:u,:n,'tour') RETURNING id").param("u", user).param("n", marker).query(Long.class).single();
            long place = jdbc.sql("INSERT INTO places(name,category,lat,lng,source,external_id,normalized_name,compact_name) VALUES ('task place','CAFE',35.8,127.1,'google',:n,'task place','taskplace') RETURNING id").param("n", marker).query(Long.class).single();
            long placeItem = jdbc.sql("""
                INSERT INTO basket_items(trip_id,added_by,item_type,place_id,source,title,category,lat,lng,status,
                  client_item_id,payload_fingerprint,provider,external_id,source_record_id,source_type,source_label,confidence,address)
                VALUES(:t,:u,'place',:p,'google',:n,'cafe',35.8,127.1,'resolved',:k,repeat('a',64),'google',:n,NULL,'google','Google',1.0,'address') RETURNING id
                """).param("t", trip).param("u", user).param("p", place).param("n", marker).param("k", marker).query(Long.class).single();
            long linkItem = jdbc.sql("""
                INSERT INTO basket_items(trip_id,added_by,item_type,source,original_url,title,status,client_item_id,
                  payload_fingerprint,provider,external_id,source_type,source_label,confidence)
                VALUES(:t,:u,'link','naver','https://example.test',:n,'unresolved',:k,repeat('b',64),'naver',
                  'https://example.test','link','naver',1.0) RETURNING id
                """).param("t", trip).param("u", user).param("n", marker).param("k", marker + "-link").query(Long.class).single();
            return List.of(placeItem, linkItem);
        });
        try {
            List<String> mutations = List.of(
                "trip_id = trip_id + 1", "added_by = added_by + 1", "client_item_id = client_item_id || 'x'",
                "payload_fingerprint = repeat('c',64)", "item_type = CASE WHEN item_type='place' THEN 'link' ELSE 'place' END",
                "place_id = coalesce(place_id,0) + 1", "source = source || 'x'",
                "original_url = coalesce(original_url,'') || 'x'", "title = title || 'x'", "thumbnail_url = coalesce(thumbnail_url,'') || 'x'",
                "category = coalesce(category,'') || 'x'", "lat = coalesce(lat,0) + 0.1", "lng = coalesce(lng,0) + 0.1",
                "cell_id = coalesce(cell_id,0) + 1", "added_at = added_at + interval '1 second'",
                "provider = coalesce(provider,'') || 'x'", "external_id = coalesce(external_id,'') || 'x'",
                "source_record_id = coalesce(source_record_id,0) + 1", "source_type = coalesce(source_type,'') || 'x'",
                "source_label = coalesce(source_label,'') || 'x'", "confidence = confidence - 0.1", "address = coalesce(address,'') || 'x'"
            );
            for (long id : ids) for (String mutation : mutations) {
                assertThatThrownBy(() -> tx().executeWithoutResult(status ->
                    jdbc.sql("UPDATE basket_items SET " + mutation + " WHERE id = :id").param("id", id).update()
                )).isInstanceOf(DataAccessException.class);
            }
            tx().executeWithoutResult(status -> ids.forEach(id ->
                jdbc.sql("UPDATE basket_items SET status='manual' WHERE id=:id").param("id", id).update()
            ));
            assertThat(jdbc.sql("SELECT status FROM basket_items WHERE id IN (:ids)").param("ids", ids).query(String.class).list()).containsOnly("manual");
        } finally {
            tx().executeWithoutResult(status -> {
                jdbc.sql("DELETE FROM trips WHERE owner_id IN (SELECT id FROM users WHERE nickname=:n)").param("n", marker).update();
                jdbc.sql("DELETE FROM users WHERE nickname=:n").param("n", marker).update();
                jdbc.sql("DELETE FROM places WHERE external_id=:n").param("n", marker).update();
            });
        }
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
}
