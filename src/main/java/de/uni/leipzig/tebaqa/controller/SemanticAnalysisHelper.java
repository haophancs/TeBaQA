package de.uni.leipzig.tebaqa.controller;

import com.hp.hpl.jena.rdf.model.RDFNode;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryEdition;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryEntry;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryPage;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryRelation;
import de.tudarmstadt.ukp.jwktl.api.RelationType;
import de.uni.leipzig.tebaqa.analyzer.Analyzer;
import de.uni.leipzig.tebaqa.helper.QueryMappingFactory;
import de.uni.leipzig.tebaqa.helper.SPARQLUtilities;
import de.uni.leipzig.tebaqa.helper.StanfordPipelineProvider;
import de.uni.leipzig.tebaqa.helper.Utilities;
import de.uni.leipzig.tebaqa.helper.WiktionaryProvider;
import de.uni.leipzig.tebaqa.model.CustomQuestion;
import de.uni.leipzig.tebaqa.model.QueryTemplateMapping;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.CoreMap;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.log4j.Logger;
import org.assertj.core.util.Sets;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SerializationHelper;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static de.uni.leipzig.tebaqa.helper.Utilities.ARGUMENTS_BETWEEN_SPACES;

public class SemanticAnalysisHelper {
    private static Logger log = Logger.getLogger(SemanticAnalysisHelper.class);
    private StanfordCoreNLP pipeline;

    public static int UNKNOWN_ANSWER_TYPE = -1;
    public static int BOOLEAN_ANSWER_TYPE = 0;
    public static int LIST_OF_RESOURCES_ANSWER_TYPE = 1;
    public static int SINGLE_RESOURCE_TYPE = 2;
    public static int NUMBER_ANSWER_TYPE = 3;
    public static int DATE_ANSWER_TYPE = 4;
    public static int STRING_ANSWER_TYPE = 5;
    public static int MIXED_LIST_ANSWER_TYPE = 6;

    public SemanticAnalysisHelper() {
        this.pipeline = StanfordPipelineProvider.getSingletonPipelineInstance();
    }

    public static int determineQueryType(String q) {
        List<String> selectIndicatorsList = Arrays.asList("list|give|show|who|when|were|what|why|whose|how|where|which".split("\\|"));
        List<String> askIndicatorsList = Arrays.asList("is|are|did|was|does".split("\\|"));
        log.debug("String question: " + q);
        String[] split = q.split("\\s+");
        List<String> firstThreeWords = new ArrayList<>();
        if (split.length > 3) {
            firstThreeWords.addAll(Arrays.asList(split).subList(0, 3));
        } else {
            firstThreeWords.addAll(Arrays.asList(split));
        }
        if (SemanticAnalysisHelper.hasAscAggregation(q)) {
            return SPARQLUtilities.SELECT_SUPERLATIVE_ASC_QUERY;
        } else if (SemanticAnalysisHelper.hasDescAggregation(q)) {
            return SPARQLUtilities.SELECT_SUPERLATIVE_DESC_QUERY;
        } else if (SemanticAnalysisHelper.hasCountAggregation(q)) {
            return SPARQLUtilities.SELECT_COUNT_QUERY;
        } else if (firstThreeWords.stream().anyMatch(s -> selectIndicatorsList.contains(s.toLowerCase()))) {
            return SPARQLUtilities.SELECT_QUERY;
        } else if (firstThreeWords.stream().anyMatch(s -> askIndicatorsList.contains(s.toLowerCase()))) {
            return SPARQLUtilities.ASK_QUERY;
        } else {
            return SPARQLUtilities.QUERY_TYPE_UNKNOWN;
        }
    }

    public Annotation annotate(String text) {
        Annotation annotation = new Annotation(text);
        pipeline.annotate(annotation);

        List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            SemanticGraph dependencyGraph = sentence.get(SemanticGraphCoreAnnotations.EnhancedDependenciesAnnotation.class);
            if (dependencyGraph == null) {
                dependencyGraph = sentence.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
            }
            //dependencyGraph.prettyPrint();
            //String compactGraph = dependencyGraph.toCompactString();

            //log.info(compactGraph);
        }


        //pipeline.prettyPrint(annotation, System.out);
        return annotation;
    }

    /**
     * Extracts the dependency graph out of a sentence. Note: Only the dependency graph of the first sentence is
     * recognized. Every following sentence will be ignored!
     *
     * @param text The string which contains the question.
     * @return The dependency graph.
     */
    public SemanticGraph extractDependencyGraph(String text) {
        Annotation annotation = new Annotation(text);
        pipeline.annotate(annotation);

        List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
        if (sentences.size() > 1) {
            log.error("There is more than one sentence to analyze: " + text);
        }
        CoreMap sentence = sentences.get(0);
        SemanticGraph dependencyGraph = sentence.get(SemanticGraphCoreAnnotations.EnhancedDependenciesAnnotation.class);
        if (dependencyGraph == null) {
            return sentence.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
        } else {
            return dependencyGraph;
        }
    }

    /**
     * Extracts a map of possible query templates and their graph patterns.
     *
     * @param questions The questions which contain a SPARQL query which will be used as template.
     * @param nodes     A list containing all entities from DBpedia's ontology.
     * @return A list which contains SPARQL query templates, divided by their number of entities and classes and by
     * their query type (ASK or SELECT).
     */
    public Map<String, QueryTemplateMapping> extractTemplates(List<CustomQuestion> questions, List<RDFNode> nodes, List<String> properties) {
        Map<String, QueryTemplateMapping> mappings = new HashMap<>();
        for (CustomQuestion question : questions) {
            String query = question.getQuery();
            QueryMappingFactory queryMappingFactory = new QueryMappingFactory(question.getQuestionText(), query, nodes, properties);
            String queryPattern = queryMappingFactory.getQueryPattern();
            boolean isSuperlativeDesc = false;
            boolean isSuperlativeAsc = false;
            boolean isCountQuery = false;

            if (queryPattern.toLowerCase().contains("order by desc") && queryPattern.toLowerCase().contains("order by desc") && queryPattern.toLowerCase().contains("limit 1")) {
                isSuperlativeDesc = true;
            } else if (queryPattern.toLowerCase().contains("order by asc") && queryPattern.toLowerCase().contains("limit 1")) {
                isSuperlativeAsc = true;
            }
            if (queryPattern.toLowerCase().contains("count")) {
                isCountQuery = true;
            }

            if (!queryPattern.toLowerCase().contains("http://dbpedia.org/resource/")
                    && !queryPattern.toLowerCase().contains("sum") && !queryPattern.toLowerCase().contains("avg")
                    && !queryPattern.toLowerCase().contains("min") && !queryPattern.toLowerCase().contains("max")
                    && !queryPattern.toLowerCase().contains("filter") && !queryPattern.toLowerCase().contains("bound")) {
                int classCnt = 0;
                int propertyCnt = 0;

                List<String> triples = Utilities.extractTriples(queryPattern);
                for (String triple : triples) {
                    Matcher argumentMatcher = ARGUMENTS_BETWEEN_SPACES.matcher(triple);
                    int argumentCnt = 0;
                    while (argumentMatcher.find()) {
                        String argument = argumentMatcher.group();
                        if (argument.startsWith("<^") && (argumentCnt == 0 || argumentCnt == 2)) {
                            classCnt++;
                        } else if (argument.startsWith("<^") && argumentCnt == 1) {
                            propertyCnt++;
                        }
                        argumentCnt++;
                    }
                }

                int finalClassCnt = classCnt;
                int finalPropertyCnt = propertyCnt;
                String graph = question.getGraph();
                int queryType = SPARQLUtilities.getQueryType(query);
                if (mappings.containsKey(graph)) {
                    QueryTemplateMapping mapping = mappings.get(graph);
                    if (mapping.getNumberOfClasses() == finalClassCnt && mapping.getNumberOfClasses() == finalPropertyCnt) {
                        if (isSuperlativeDesc) {
                            mapping.addSelectSuperlativeDescTemplate(queryPattern, question.getQuery());
                        } else if (isSuperlativeAsc) {
                            mapping.addSelectSuperlativeAscTemplate(queryPattern, question.getQuery());
                        } else if (isCountQuery) {
                            mapping.addCountTemplate(queryPattern, question.getQuery());
                        } else if (queryType == SPARQLUtilities.SELECT_QUERY) {
                            mapping.addSelectTemplate(queryPattern, question.getQuery());
                        } else if (queryType == SPARQLUtilities.ASK_QUERY) {
                            mapping.addAskTemplate(queryPattern, question.getQuery());
                        } else if (queryType == SPARQLUtilities.QUERY_TYPE_UNKNOWN) {
                            log.error("Unknown query type: " + query);
                        }
                    }
                } else {
                    QueryTemplateMapping mapping = new QueryTemplateMapping(classCnt, propertyCnt);
                    if (isSuperlativeDesc) {
                        mapping.addSelectSuperlativeDescTemplate(queryPattern, question.getQuery());
                    } else if (isSuperlativeAsc) {
                        mapping.addSelectSuperlativeAscTemplate(queryPattern, question.getQuery());
                    } else if (isCountQuery) {
                        mapping.addCountTemplate(queryPattern, question.getQuery());
                    } else if (queryType == SPARQLUtilities.SELECT_QUERY) {
                        mapping.addSelectTemplate(queryPattern, question.getQuery());
                    } else if (queryType == SPARQLUtilities.ASK_QUERY) {
                        mapping.addAskTemplate(queryPattern, question.getQuery());
                    }
                    //create a new mapping class
                    mappings.put(graph, mapping);
                }
                //log.info(queryPattern);
            }
        }
        return mappings;
    }

    public static Map<String, String> getLemmas(String q) {
        Map<String, String> lemmas = new HashMap<>();
        Annotation annotation = new Annotation(q);
        StanfordCoreNLP pipeline = StanfordPipelineProvider.getSingletonPipelineInstance();
        pipeline.annotate(annotation);
        List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            List<CoreLabel> labels = sentence.get(CoreAnnotations.TokensAnnotation.class);
            for (CoreLabel token : labels) {
                String word = token.get(CoreAnnotations.TextAnnotation.class);
                String lemma = token.get(CoreAnnotations.LemmaAnnotation.class);
                lemmas.put(word, lemma);
            }
        }
        return lemmas;
    }

    public static Map<String, String> getPOS(String q) {
        Map<String, String> pos = new HashMap<>();
        Annotation annotation = new Annotation(q);
        StanfordCoreNLP pipeline = StanfordPipelineProvider.getSingletonPipelineInstance();
        pipeline.annotate(annotation);
        List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            List<CoreLabel> labels = sentence.get(CoreAnnotations.TokensAnnotation.class);
            for (CoreLabel token : labels) {
                String word = token.get(CoreAnnotations.TextAnnotation.class);
                String posAnnotation = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                pos.put(word, posAnnotation);
            }
        }
        return pos;
    }

    /**
     * Checks if a given sentence uses superlatives like first, least and so on which are indicators for aggregation queries.
     *
     * @param sentence A string which contains a sentence.
     * @return If the sentence contains keywords which are used in ascending aggregation queries.
     */
    public static boolean hasAscAggregation(String sentence) {
        String[] ascIndicators = new String[]{"first", "oldest", "smallest", "lowest", "shortest", "least"};
        String[] words = sentence.split("\\W+");
        return Arrays.stream(words).anyMatch(Arrays.asList(ascIndicators)::contains);
    }

    /**
     * Checks if a given sentence uses superlatives like largest, last, highest and so on which are indicators for aggregation queries.
     *
     * @param sentence A string which contains a sentence.
     * @return If the sentence contains keywords which are used in descending aggregation queries.
     */
    public static boolean hasDescAggregation(String sentence) {
        String[] descIndicators = new String[]{"largest", "last", "highest", "most", "biggest", "youngest", "longest", "tallest"};
        String[] words = sentence.split("\\W+");
        return Arrays.stream(words).anyMatch(Arrays.asList(descIndicators)::contains);
    }


    public static boolean hasCountAggregation(String sentence) {
        return sentence.toLowerCase().trim().startsWith("how many") || sentence.toLowerCase().trim().startsWith("how much");
    }

    /**
     * Classifies a question and tries to find the best matching graph pattern for it's SPARQL query.
     *
     * @param question  The question which shall be classified.
     * @param graphList A list containing every possible graph pattern.
     * @return The predicted graph pattern.
     */
    String classifyInstance(CustomQuestion question, HashSet<String> graphList) {
        ArrayList<Attribute> attributes = new ArrayList<>();

        List<String> filter = new ArrayList<>();
        filter.add("Filter");
        filter.add("noFilter");

        List<String> optional = new ArrayList<>();
        optional.add("Optional");
        optional.add("noOptional");

        List<String> limit = new ArrayList<>();
        limit.add("Limit");
        limit.add("noLimit");

        List<String> orderBy = new ArrayList<>();
        orderBy.add("OrderBy");
        orderBy.add("noOrderBy");

        List<String> union = new ArrayList<>();
        union.add("Union");
        union.add("noUnion");

        Attribute filterAttribute = new Attribute("filter", filter);
        Attribute optionalAttribute = new Attribute("optional", optional);
        Attribute limitAttribute = new Attribute("limit", limit);
        Attribute orderByAttribute = new Attribute("orderBy", orderBy);
        Attribute unionAttribute = new Attribute("union", union);

        attributes.add(filterAttribute);
        attributes.add(optionalAttribute);
        attributes.add(limitAttribute);
        attributes.add(orderByAttribute);
        attributes.add(unionAttribute);

        Attribute classAttribute = new Attribute("class", new ArrayList<>(graphList));
        attributes.add(classAttribute);

        Analyzer analyzer = new Analyzer(attributes);
        Instances dataset = new Instances("testdata", analyzer.fvWekaAttributes, 1);
        dataset.setClassIndex(dataset.numAttributes() - 1);
        Instance instance = analyzer.analyze(question.getQuestionText());
        instance.setDataset(dataset);
        instance.setMissing(classAttribute);
        String[] classes = new String[graphList.size()];
        int i = 0;
        for (Enumeration<Object> e = classAttribute.enumerateValues(); e.hasMoreElements(); ) {
            String graph = (String) e.nextElement();
            classes[i] = graph;
            i++;
        }

        String predictedGraph = "";
        try {
            Classifier cls = (Classifier) SerializationHelper.read("./src/main/resources/randomCommittee.model");
            double predictedClass = cls.classifyInstance(instance);

            predictedGraph = instance.classAttribute().value((int) predictedClass);
            //log.info(String.format("Question: '%s' \nPredicted class: %s", question.getQuestionText(), predictedGraph));
            //log.info("Classified instance: " + instance);

        } catch (Exception e) {
            log.error("Unable to load weka model file!", e);
        }
        if (predictedGraph.equals(question.getGraph())) {
            //log.info("Predicted class is correct.");
        } else {
            //log.info("Predicted class is incorrect! Predicted: " + predictedGraph + "; actual: " + question.getGraph());
        }
        return predictedGraph;
    }

    /**
     * Checks, if a question is inside a Map.
     *
     * @param map  The map in which the question is not is not.
     * @param text The question text.
     * @return true if the text is inside, false otherwise.
     */
    boolean containsQuestionText(Map<String, String> map, String text) {
        boolean isInside = false;
        for (Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue().equals(text)) {
                isInside = true;
                break;
            }
        }
        return isInside;
    }

    /**
     * Removes all variables, prefixes, newlines, standard keywords like ASK, SELECT, WHERE, DISTINCT.
     *
     * @param queryString The sparql query string.
     * @return A string which only contains sparql modifiers, a '?' as placeholder for a variable and '<>' as
     * placeholders for strings like this: { <> <> ? . ? <> ? FILTER regex( ? , ? ) }
     */
    String cleanQuery(String queryString) {
        Query query = QueryFactory.create(queryString);
        query.setPrefixMapping(null);
        return query.toString().trim()
                //replace newlines with space
                .replaceAll("\n", " ")
                //replace every variable with ?
                .replaceAll("\\?[a-zA-Z\\d]+", " ? ")
                //replace every number(e.g. 2 or 2.5) with a ?
                .replaceAll("\\s+\\d+\\.?\\d*", " ? ")
                //replace everything in quotes with ?
                .replaceAll("([\"'])(?:(?=(\\\\?))\\2.)*?\\1", " ? ")
                //remove everything between <>
                .replaceAll("<\\S*>", " <> ")
                //remove all SELECT, ASK, DISTINCT and WHERE keywords
                .replaceAll("(?i)(select|ask|where|distinct)", " ")
                //remove every consecutive spaces
                .replaceAll("\\s+", " ");
    }

    public List<IndexedWord> getDependencySequence(SemanticGraph semanticGraph) {
        IndexedWord firstRoot = semanticGraph.getFirstRoot();
        List<IndexedWord> sequence = getDependenciesFromEdge(firstRoot, semanticGraph);
        log.debug(sequence);
        return sequence;
    }

    private static List<IndexedWord> getDependenciesFromEdge(IndexedWord root, SemanticGraph semanticGraph) {
        final String posExclusion = "DT|IN|WDT|W.*|\\.";
        final String lemmaExclusion = "have|do|be|many|much|give|call|list";
        List<IndexedWord> sequence = new ArrayList<>();
        String rootPos = root.get(CoreAnnotations.PartOfSpeechAnnotation.class);
        String rootLemma = root.get(CoreAnnotations.LemmaAnnotation.class);
        if (!rootPos.matches(posExclusion) && !rootLemma.matches(lemmaExclusion)) {
            sequence.add(root);
        }
        Set<IndexedWord> childrenFromRoot = semanticGraph.getChildren(root);

        for (IndexedWord word : childrenFromRoot) {
            String wordPos = word.get(CoreAnnotations.PartOfSpeechAnnotation.class);
            String wordLemma = word.get(CoreAnnotations.LemmaAnnotation.class);
            if (!wordPos.matches(posExclusion) && !wordLemma.matches(lemmaExclusion)) {
                sequence.add(word);
            }
            List<IndexedWord> children = semanticGraph.getChildList(word);
            //In some cases a leaf has itself as children which results in endless recursion.
            if (children.contains(root)) {
                children.remove(root);
            }
            for (IndexedWord child : children) {
                sequence.addAll(getDependenciesFromEdge(child, semanticGraph));
            }
        }
        return sequence;
    }

    public static int detectQuestionAnswerType(String question) {
        SemanticAnalysisHelper semanticAnalysisHelper = new SemanticAnalysisHelper();
        SemanticGraph semanticGraph = semanticAnalysisHelper.extractDependencyGraph(question);
        List<IndexedWord> sequence = semanticAnalysisHelper.getDependencySequence(semanticGraph);
        Pattern pattern = Pattern.compile("\\w+");
        Matcher m = pattern.matcher(question);
        if (m.find()) {
            if (m.group().toLowerCase().matches("is|are|did|was|does")) {
                return BOOLEAN_ANSWER_TYPE;
            }
        }
        if (question.toLowerCase().startsWith("how many") || question.toLowerCase().startsWith("how much")) {
            return NUMBER_ANSWER_TYPE;
        }
        if (question.toLowerCase().startsWith("when")) {
            return DATE_ANSWER_TYPE;
        }
        for (IndexedWord word : sequence) {
            String posTag = word.get(CoreAnnotations.PartOfSpeechAnnotation.class);
            if (posTag.equalsIgnoreCase("NNS") || posTag.equalsIgnoreCase("NNPS")) {
                return LIST_OF_RESOURCES_ANSWER_TYPE;
            } else if (posTag.equalsIgnoreCase("NN") || posTag.equalsIgnoreCase("NNP")) {
                return SINGLE_RESOURCE_TYPE;
            }
        }
        return UNKNOWN_ANSWER_TYPE;
    }

    Set<String> getBestAnswer(List<Map<Integer, List<String>>> results, StringBuilder logMessage, int expectedAnswerType, boolean forceResult) {
        List<Map<Integer, List<String>>> suitableAnswers = new ArrayList<>();
        List<String> bestAnswer = new ArrayList<>();

        if (expectedAnswerType == SemanticAnalysisHelper.SINGLE_RESOURCE_TYPE) {
            //A list might contain the correct answer too
            results.forEach(result -> {
                if (result.containsKey(SemanticAnalysisHelper.LIST_OF_RESOURCES_ANSWER_TYPE)) {
                    List<String> resultWithMatchingType = result.get(SemanticAnalysisHelper.LIST_OF_RESOURCES_ANSWER_TYPE);
                    if (!resultWithMatchingType.isEmpty()) {
                        Map<Integer, List<String>> answer = new HashMap<>();
                        answer.put(SemanticAnalysisHelper.LIST_OF_RESOURCES_ANSWER_TYPE, resultWithMatchingType);
                        suitableAnswers.add(answer);
                    }
                }
            });
        } else if (expectedAnswerType == SemanticAnalysisHelper.LIST_OF_RESOURCES_ANSWER_TYPE) {
            //A single answer might be a partly correct answer
            results.forEach(result -> {
                if (result.containsKey(SemanticAnalysisHelper.SINGLE_RESOURCE_TYPE)) {
                    List<String> resultWithMatchingType = result.get(SemanticAnalysisHelper.SINGLE_RESOURCE_TYPE);
                    if (!resultWithMatchingType.isEmpty()) {
                        Map<Integer, List<String>> answer = new HashMap<>();
                        answer.put(SemanticAnalysisHelper.SINGLE_RESOURCE_TYPE, resultWithMatchingType);
                        suitableAnswers.add(answer);
                    }
                }
            });
        }

        results.forEach(result -> {
            if (result.containsKey(expectedAnswerType)) {
                List<String> resultWithMatchingType = result.get(expectedAnswerType);
                if (!resultWithMatchingType.isEmpty()) {
                    Map<Integer, List<String>> answer = new HashMap<>();
                    answer.put(expectedAnswerType, resultWithMatchingType);
                    suitableAnswers.add(answer);
                }
            }
        });
        if (suitableAnswers.size() == 1) {
            Map<Integer, List<String>> answer = suitableAnswers.get(0);
            Optional<List<String>> first = answer.values().stream().findFirst();
            if (first.isPresent()) {
                return Sets.newHashSet(first.get());
            } else {
                return new HashSet<>();
            }
        } else if (suitableAnswers.isEmpty() && forceResult) {
            //If there is no suitable result fallback to the other results
            List<String> finalBestAnswer = bestAnswer;
            results.stream()
                    .map(Map::values)
                    .forEach(values -> finalBestAnswer.addAll(values.stream()
                            .flatMap(List::stream)
                            .collect(Collectors.toList())));
            bestAnswer = finalBestAnswer;
        } else if (suitableAnswers.size() > 1) {
            List<String> answersWithMatchingType = suitableAnswers.stream()
                    .filter(answer -> answer.containsKey(expectedAnswerType))
                    .map(answer -> answer.getOrDefault(expectedAnswerType, new ArrayList<>()))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            if (expectedAnswerType == SemanticAnalysisHelper.BOOLEAN_ANSWER_TYPE) {
                int trueCount = Math.toIntExact(answersWithMatchingType.stream().filter(Boolean::valueOf).count());
                int falseCount = Math.toIntExact(answersWithMatchingType.stream().filter(a -> !Boolean.valueOf(a)).count());
                Set<String> set = new HashSet<>();
                if (trueCount >= falseCount) {
                    set.add("true");
                } else {
                    set.add("false");
                }
                return set;
            } else if (answersWithMatchingType.size() != 0) {
                return Sets.newHashSet(answersWithMatchingType);
            } else {
                return suitableAnswers.stream().map(answer -> answer.getOrDefault(expectedAnswerType, new ArrayList<>()))
                        .flatMap(Collection::stream)
                        .collect(Collectors.toSet());
            }
        }
        return Sets.newHashSet(bestAnswer);
    }

    private Set<String> getBestAnswerByPageRank(List<List<String>> suitableAnswers) {
        Set<String> bestAnswer = new HashSet<>();
        //If there are multiple suitable answers, use the one(s) with the best avg page rank
        final Double[] bestAvgPageRank = {0.0};
        suitableAnswers.forEach(values -> {
            List<Double> pageRanks = new ArrayList<>();
            values.forEach(s -> pageRanks.add(SPARQLUtilities.getPageRank(s)));
            OptionalDouble average = pageRanks.stream().mapToDouble(value -> value).average();
            Double avgPageRank = average.isPresent() ? average.getAsDouble() : 0.0;
            if (avgPageRank >= bestAvgPageRank[0]) {
                bestAnswer.addAll(new ArrayList<>(values));
                bestAvgPageRank[0] = avgPageRank;
            }
        });
        return bestAnswer;
    }

    public static List<String> getHypernymsFromWiktionary(String s) {
        List<String> hypernyms = new ArrayList<>();
        IWiktionaryEdition wiktionaryInstance = WiktionaryProvider.getWiktionaryInstance();
        IWiktionaryPage page = wiktionaryInstance.getPageForWord(s);
        if (page != null) {
            for (IWiktionaryEntry entry : page.getEntries()) {
                for (IWiktionaryRelation relation : entry.getRelations(RelationType.HYPERNYM)) {
                    hypernyms.add(relation.getTarget());
                }
            }
        }
        return hypernyms;
    }
}
