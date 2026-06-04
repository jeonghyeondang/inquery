package ai.inquery.server.web.api.controller.rdb.request;


import lombok.Data;

@Data
public class TableMilvusQueryRequest extends TableBriefQueryRequest {

    private String apikey;
}
