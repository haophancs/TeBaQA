package de.uni.leipzig.tebaqa.analyzerGerman;

import org.aksw.mlqa.analyzer.IAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.core.Attribute;
import weka.core.FastVector;

public class QuestionWord implements IAnalyzer {
    Logger log = LoggerFactory.getLogger(org.aksw.mlqa.analyzer.questionword.QuestionWord.class);
    private Attribute attribute = null;
    String AuxVerb = "Ist||Sind||Hat";
    String Commands = "Gib||Zeige";

    public QuestionWord() {
        FastVector attributeValues = new FastVector();
        attributeValues.addElement("Wer");
        attributeValues.addElement("Was");
        attributeValues.addElement("Wann");
        attributeValues.addElement("Wo");
        attributeValues.addElement("Welche");
        attributeValues.addElement(this.Commands);
        attributeValues.addElement(this.AuxVerb);
        attributeValues.addElement("Wie");
        attributeValues.addElement("Misc");
        this.attribute = new Attribute("QuestionWord", attributeValues);
    }

    public Object analyze(String q) {
        this.log.debug("String question: " + q);
        String[] split = q.split("\\s+");
        int indexOfValue = this.attribute.indexOfValue(split[0]);
        if (indexOfValue < 0) {
            if (split[0].matches(this.AuxVerb)) {
                indexOfValue = this.attribute.indexOfValue(this.AuxVerb);
            } else if (split[0].matches(this.Commands)) {
                indexOfValue = this.attribute.indexOfValue(this.Commands);
            } else {
                indexOfValue = this.attribute.indexOfValue("Misc");
            }
        }

        return this.attribute.value(indexOfValue);
    }

    public Attribute getAttribute() {
        return this.attribute;
    }
}
