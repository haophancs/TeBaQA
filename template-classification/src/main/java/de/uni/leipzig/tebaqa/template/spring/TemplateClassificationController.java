package de.uni.leipzig.tebaqa.template.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.uni.leipzig.tebaqa.tebaqacommons.model.QueryTemplateResponseBean;
import de.uni.leipzig.tebaqa.tebaqacommons.model.QueryType;
import de.uni.leipzig.tebaqa.tebaqacommons.nlp.ISemanticAnalysisHelper;
import de.uni.leipzig.tebaqa.tebaqacommons.nlp.SemanticAnalysisHelperEnglish;
import de.uni.leipzig.tebaqa.template.model.QueryTemplateMapping;
import de.uni.leipzig.tebaqa.template.service.WekaClassifier;
import org.apache.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class TemplateClassificationController {

    private static final Logger LOGGER = Logger.getLogger(TemplateClassificationController.class.getName());
    private static final WekaClassifier classifier = WekaClassifier.getDefaultClassifier();
    private static final ISemanticAnalysisHelper semanticAnalysisHelper = new SemanticAnalysisHelperEnglish();

    @RequestMapping(method = RequestMethod.GET, path = "/test-tc")
    public String testGet(HttpServletResponse response) {
        return ResponseEntity.status(HttpStatus.OK).body("GET for /test-tc success").toString();
    }


    @RequestMapping(method = RequestMethod.POST, path = "/classify-template")
    public QueryTemplateResponseBean classifyTemplate(@RequestParam String question,
                                                      @RequestParam(required = false, defaultValue = "en") String lang,
                                                      HttpServletResponse response) throws JsonProcessingException {
        LOGGER.info(String.format("/classify-template received POST request with: question='%s'", question));

        if (question.isEmpty()) {
            LOGGER.error("Received request with empty query parameter!");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a valid question");
        }

        QueryTemplateResponseBean templateResponseBean = new QueryTemplateResponseBean();
        templateResponseBean.setQuestion(question);
        templateResponseBean.setLang(lang);

        String graph = classifier.classifyInstance(question);
        LOGGER.info(String.format("%s -> %s", question, graph));

        QueryType queryType = semanticAnalysisHelper.mapQuestionToQueryType(question);
        QueryTemplateMapping templateMapping = classifier.getQueryTemplatesFor(graph);

        Set<String> templates;
        if (templateMapping == null)
            // In case QueryTemplateMapping cannot be found for the classified graph then,
            // from all QueryTemplateMapping, get the templates of the given queryType.
            templates = classifier.getAllQueryTemplates().stream().flatMap(queryTemplateMapping -> queryTemplateMapping.getTemplatesFor(queryType).stream()).collect(Collectors.toSet());
        else
            // If the QueryTemplateMapping is found for the classified graph,
            // then get the templates from that single QueryTemplateMapping
            templates = templateMapping.getTemplatesFor(queryType);

        templateResponseBean.setTemplates(new ArrayList<>(templates));

//        return ResponseEntity.status(HttpStatus.OK).body(JSONUtils.convertToJSONString(templateResponseBean)).toString();
        return templateResponseBean;
    }


}
