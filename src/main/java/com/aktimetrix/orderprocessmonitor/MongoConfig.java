package com.aktimetrix.orderprocessmonitor;

import org.javatuples.Pair;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;

@Configuration
@EnableMongoRepositories(basePackages = {"com.aktimetrix.core.repository",
        "com.aktimetrix.core.referencedata.repository",
        "com.aktimetrix.core.tenant.repository"})
public class MongoConfig {
    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(
                Arrays.asList(
                        new StringToPairConverter(),
                        new PairToStringConverter(),
                        new ZonedDateTimeReadConverter(),
                        new ZonedDateTimeWriteConverter()));
    }

    @WritingConverter
    public static class PairToStringConverter implements Converter<Pair<String, String>, String> {
        @Override
        public String convert(Pair source) {
            return source.getValue0() + "#" + source.getValue1();
        }
    }

    @ReadingConverter
    public static class StringToPairConverter implements Converter<String, Pair<String, String>> {
        @Override
        public Pair<String, String> convert(String source) {
            final String[] split = source.split("#");
            return Pair.with(split[0], split[1]);
        }
    }

    @ReadingConverter
    public static class ZonedDateTimeReadConverter implements Converter<Date, ZonedDateTime> {
        @Override
        public ZonedDateTime convert(Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC);
        }
    }

    @WritingConverter
    public static class ZonedDateTimeWriteConverter implements Converter<ZonedDateTime, Date> {
        @Override
        public Date convert(ZonedDateTime zonedDateTime) {
            return Date.from(zonedDateTime.toInstant());
        }
    }

}
