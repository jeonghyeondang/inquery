package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

@Getter
@Setter
@TableName("reference_document")
public class ReferenceDocumentDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Date gmtCreate;
    private Date gmtModified;

    private Long userId;
    private String filename;
    private String mimeType;
    private String kind;
    private Long sizeBytes;
    private String fileHash;
    private String storageType;

    @TableField(select = false)
    private byte[] content;

    private String storagePath;
    private String extractedText;
    private Integer chunkCount;
    private String indexStatus;
    private String indexError;
    private String deleted;
}
