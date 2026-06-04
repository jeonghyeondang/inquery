package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Presenton Presentation Entity
 */
@Data
@TableName("presenton_presentation")
public class PresentonPresentationDO {
    
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    
    @TableField("gmt_create")
    private Date gmtCreate;
    
    @TableField("gmt_modified")
    private Date gmtModified;
    
    @TableField("user_id")
    private Long userId;
    
    @TableField("title")
    private String title;
    
    @TableField("content")
    private String content;
    
    @TableField("n_slides")
    private Integer nSlides;
    
    @TableField("language")
    private String language;
    
    @TableField("tone")
    private String tone;
    
    @TableField("verbosity")
    private String verbosity;
    
    @TableField("template")
    private String template;
    
    @TableField("outlines_json")
    private String outlinesJson;
    
    @TableField("structure_json")
    private String structureJson;
    
    @TableField("status")
    private String status;
}
