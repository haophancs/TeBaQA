package de.uni.leipzig.tebaqa.helper;


import com.google.common.collect.Sets;
import de.uni.leipzig.tebaqa.controller.ElasticSearchEntityIndex;
import de.uni.leipzig.tebaqa.controller.SemanticAnalysisHelper;
import de.uni.leipzig.tebaqa.model.*;
import edu.stanford.nlp.simple.Sentence;
import joptsimple.internal.Strings;

import org.aksw.qa.annotation.index.IndexDBO_classes;
import org.aksw.qa.annotation.index.IndexDBO_properties;
import org.aksw.qa.commons.datastructure.Entity;
import org.aksw.qa.commons.nlp.nerd.Spotlight;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Resource;
import org.apache.log4j.Logger;
import org.ehcache.PersistentCacheManager;
import org.jetbrains.annotations.NotNull;
import weka.core.Stopwords;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

//import static de.uni.leipzig.tebaqa.controller.SemanticAnalysisHelper.getLemmas;
import static de.uni.leipzig.tebaqa.helper.TextUtilities.NON_WORD_CHARACTERS_REGEX;
import static de.uni.leipzig.tebaqa.helper.Utilities.getLevenshteinRatio;
import static java.lang.String.join;
import static org.apache.commons.lang3.StringUtils.getLevenshteinDistance;

/**
 * Creates a Mapping between a part-of-speech tag sequence and a SPARQL query.
 * Algorithm:
 * <br>Input: Question, dependencySequencePos(mapping between the words of the question and their part-of-speech tag),
 * QueryPattern from <i>sparqlQuery</i></br>
 * <ol>
 * <li>Get Named Entities of the <i>question</i> from DBpedia Spotlight.</li>
 * <li>Replace every named entity from step 1 in the <i><QueryPattern</i> with its part-of-speech tag.
 * If there is no exactly same entity to replace in step go to step 3.</li>
 * <li>Find possible matches based on string similarities:
 * <ol>
 * <li type="1">Create a List with all possible neighbor co-occurrences from the words in <i>Question</i>. Calculate the
 * levenshtein distance between every neighbor co-occurrence permutation and the entity from Spotlight</li>
 * <li type="1">If the distance of the likeliest group of neighbor co-occurrences is lower than 0.5 and the ratio between
 * the 2 likeliest group of words is smaller than 0.7, replace the resource in the <i>QueryPattern</i> with the
 * part-of-speech tags of the word group</li>
 * </ol>
 * </li>
 * <li>For every resource in the <i>QueryPattern</i> which isn't detected in the steps above, search for a
 * similar(based on levenshtein distance, see step 3) string in the question.</li>
 * <p>
 * </ol>
 */
public class QueryMappingFactoryLabels {

    private int queryType;
    private static Logger log = Logger.getLogger(QueryMappingFactory.class);
    private String queryPattern;
    private String question;
    //private List<RDFNode> ontologyNodes;
    //private List<String> properties;
    private Map<String, String> entitiyToQuestionMapping;
    private Map<String, String> entitiyToQuestionMappingWithSynonyms;
    private boolean entitiyToQuestionMappingWasSet;
    private boolean entitiyToQuestionMappingWithSynonymsWasSet;
    private Set<String> ontologyURIs;
    //private Configuration pattyPhrases;
    private PersistentCacheManager cacheManager;
    //private Patty_relations patty_relations;
    private ElasticSearchEntityIndex entityIndex;
    ResourceLinker resourceLinker;
    WordsGenerator wordsGenerator;
    RelationsGenerator relationsGenerator;
    private SemanticAnalysisHelper semanticAnalysisHelper;
    public QueryMappingFactoryLabels(String question, String sparqlQuery,SemanticAnalysisHelper semanticAnalysisHelper) {
        //this.ontologyNodes = ontologyNodes;
        wordsGenerator=new WordsGenerator();
        ontologyURIs = new HashSet<>();
//        relationsGenerator=new RelationsGenerator();
        this.semanticAnalysisHelper=semanticAnalysisHelper;
        //ontologyNodes.forEach(rdfNode -> ontologyURIs.add(rdfNode.toString()));
        //this.properties = properties;
        this.queryType = semanticAnalysisHelper.determineQueryType(question);
        this.entitiyToQuestionMapping = new HashMap<>();
        this.entitiyToQuestionMappingWasSet = false;
        this.entitiyToQuestionMappingWithSynonyms = new HashMap<>();
        this.entitiyToQuestionMappingWithSynonymsWasSet = false;
        //this.pattyPhrases = PattyPhrasesProvider.getPattyPhrases();
        //this.patty_relations = null;

        this.question = question;
        String queryString = SPARQLUtilities.resolveNamespaces(sparqlQuery);

        this.cacheManager = CacheProvider.getSingletonCacheInstance();
        try {
            entityIndex= new ElasticSearchEntityIndex();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // queryString.replaceAll("<(.*?)>", )
        int i = 0;
        queryString = queryString.replace("<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>","a");

        String regex = "<(.+?)>";
        Pattern pattern = Pattern.compile(regex);
        Matcher m = pattern.matcher(queryString);
        HashMap<String,Integer>mappedUris=new HashMap<>();
        while (m.find()) {
            String group = m.group();
            if (!group.contains("^")&&!group.contains("http://www.w3.org/2001/XMLSchema")) {
                //if(!wellKnownPredicates.contains(Pattern.quote(group))) {
                    if (!mappedUris.containsKey(Pattern.quote(group)))
                        mappedUris.put(Pattern.quote(group), i);
                    queryString = queryString.replaceFirst(Pattern.quote(group), "res/" + mappedUris.get(Pattern.quote(group)) + ">");
                    i++;
                //}
            }
        }
        this.queryPattern = queryString
                .replaceAll("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }



    private String replaceSpotlightEntities(Map<String, String> dependencySequencePos, List<String> permutations,
                                            String queryPattern, Map<String, List<Entity>> spotlightEntities) {
        final String[] tmpQueryPattern = new String[1];
        tmpQueryPattern[0] = queryPattern;
        if (spotlightEntities.size() > 0) {
            spotlightEntities.get("en").forEach((Entity entity) -> {
                        String label = entity.getLabel();
                        List<Resource> uris = entity.getUris();
                        String[] words = label.split("\\s");
                        //Replace every named entity with its part-of-speech tag
                        //e.g.: <http://dbpedia.org/resource/Computer_science> => computer science => NN0 NN1 => ^NN0_NN1^
                        for (Resource uri : uris) {
                            String newQueryTemplate;

                            List<String> wordPos = new ArrayList<>();
                            for (String word : words) {
                                wordPos.add(dependencySequencePos.get(word));
                            }
                            String wordPosReplacement = "^" + join("_", wordPos) + "^";
                            if (tmpQueryPattern[0].toLowerCase().contains("<" + uri.toString().toLowerCase() + ">")) {
                                newQueryTemplate = tmpQueryPattern[0].replace(uri.toString(), wordPosReplacement);
                            } else {
                                //get the most similar word
                                TreeMap<Double, String> distances = getLevenshteinDistances(permutations, uri.getLocalName());
                                newQueryTemplate = conditionallyReplaceResourceWithPOSTag(dependencySequencePos, tmpQueryPattern[0],
                                        uri.toString(), distances);
                            }
                            tmpQueryPattern[0] = newQueryTemplate;
                        }
                    }
            );
        }
        return tmpQueryPattern[0];
    }

    static Map<String, List<Entity>> extractSpotlightEntities(String question) {
        //get named entities (consisting of one or multiple words) from DBpedia's Spotlight
        Spotlight spotlight = Utilities.createCustomSpotlightInstance("http://model.dbpedia-spotlight.org/en/annotate");
        spotlight.setConfidence(0.4);
        spotlight.setSupport("20");
        return spotlight.getEntities(question);
    }

    private String conditionallyReplaceResourceWithPOSTag(Map<String, String> dependencySequencePos,
                                                          String stringWithResources, String uriToReplace,
                                                          TreeMap<Double, String> distances) {
        String newString = stringWithResources;

        //Check if the difference between the two shortest distances is big enough
        if (distances.size() > 1) {
            Object[] keys = distances.keySet().toArray();
            //The thresholds are based on testing and might be suboptimal.
            if ((double) keys[0] < 0.5 && (double) keys[0] / (double) keys[1] < 0.7) {
                List<String> posList = new ArrayList<>();
                String[] split = distances.firstEntry().getValue().split(" ");
                for (String aSplit : split) {
                    posList.add(dependencySequencePos.get(aSplit));
                }
                if (newString.contains("<" + uriToReplace + ">")) {
                    newString = newString.replace(uriToReplace, "^" + join("_", posList) + "^");
                }
            }
        }
        return newString;
    }

    @NotNull
    private TreeMap<Double, String> getLevenshteinDistances(List<String> permutations, String string) {
        TreeMap<Double, String> distances = new TreeMap<>();
        permutations.forEach((word) -> {
            int lfd = getLevenshteinDistance(string, word);
            double ratio = ((double) lfd) / (Math.max(string.length(), word.length()));
            distances.put(ratio, word);
        });
        return distances;
    }

    private static List<String> getNeighborCoOccurrencePermutations(String[] s) {
        List<String> permutations = new ArrayList<>();
        for (int i = 0; i <= s.length; i++) {
            for (int y = 1; y <= s.length - i; y++) {
                if (y - i < 6) {
                    permutations.add(join(" ", Arrays.asList(s).subList(i, i + y)));
                }
            }
        }
        return permutations;
    }
    public static List<CooccurenceGroup> getNeighborCoOccurrencePermutationsGroups(String[] s) {
        List<CooccurenceGroup> permutations = new ArrayList<>();
        for (int i = 0; i <= s.length; i++) {
            CooccurenceGroup group=new CooccurenceGroup();
            for (int y = 1; y <= s.length - i; y++) {
                if (y - i < 6) {
                    group.addCooccurence((join(" ", Arrays.asList(s).subList(i, i + y))),null);
                }

            }
            if(group.getCoOccurences().size()>0)
                permutations.add(group);
        }
        return permutations;
    }

    public static List<String> getNeighborCoOccurrencePermutations(List<String> s) {
        return getNeighborCoOccurrencePermutations(s.toArray(new String[0]));
    }

    private List<List<Integer>> createDownwardCountingPermutations(int a, int b) {
        List<List<Integer>> permutations = new ArrayList<>();
        for (int i = a; i >= 0; i--) {
            for (int y = b; y >= 0; y--) {
                List<Integer> newPermutation = new ArrayList<>();
                newPermutation.add(i);
                newPermutation.add(y);
                permutations.add(newPermutation);
            }
        }
        permutations.sort((List a1, List a2) -> ((int) a2.get(0) + (int) a2.get(1)) - ((int) a1.get(0) + (int) a1.get(1)));
        return permutations;
    }

    /**
     * Creates a SPARQL Query Pattern like this: SELECT DISTINCT ?uri WHERE { ^NNP_0 ^VBZ_0 ?uri . }
     * Every entity which is recognized with the DBPedia Spotlight API is replaced by it's part-of-speech Tag.
     *
     * @return A string with part-of-speech tag placeholders.
     */
    public String getQueryPattern() {
        return queryPattern;
    }

    public Set<String> generateQueries(Map<String, QueryTemplateMapping> mappings, String graph, boolean useSynonyms) {

        if (!this.entitiyToQuestionMappingWasSet) {
            entitiyToQuestionMapping.putAll(extractEntities(question));
            this.entitiyToQuestionMappingWasSet = true;
        }
        /*} else if (useSynonyms && !this.entitiyToQuestionMappingWithSynonymsWasSet) {
            entitiyToQuestionMappingWithSynonyms.putAll(extractEntitiesUsingSynonyms(question));
            this.entitiyToQuestionMappingWithSynonymsWasSet = true;
        }*/

        List<String> suitableMappings = getSuitableMappings(mappings, queryType, graph);
        Set<String> queries;
        //if (!useSynonyms) {
            queries = fillPatterns(entitiyToQuestionMapping, suitableMappings);
        /*} else {
            Map<String, String> m = new HashMap<>();
            m.putAll(entitiyToQuestionMapping);
            m.putAll(entitiyToQuestionMappingWithSynonyms);
            if (m.size() <= 30) {
                queries = fillPatterns(m, suitableMappings);
            } else {
                queries = new HashSet<>();
                log.error("There are too many entities, skipping this query");
            }
        }*/
        return queries;
    }
    public HashMap<String,String> generateQueriesWithResourceLinker(Map<String, QueryTemplateMapping> mappings, String graph, ResourceLinker links) {

        List<String> suitableMappings = getSuitableMappings(mappings, queryType, graph);
        HashMap<String,String> queries=new HashMap<>();
        //if (!useSynonyms) {

        for (String pattern : suitableMappings) {

            HashMap<String,String> p=Utilities.fillWithTuples(pattern, links);
            if(p!=null)
                queries.putAll(p);
        }

        return queries;
    }
    public List<RatedQuery> generateQueries(Map<String, QueryTemplateMapping> mappings, String graph, FillTemplatePatternsWithResources tripleGenerator) {
        List<String> suitableMappings = getSuitableMappings(mappings, queryType, graph);
        System.out.println(String.format("Generating queries (query type, suitable mappings found): %s, %s", queryType, suitableMappings.size()));
        List<RatedQuery> queries=new ArrayList<>();
        //if (!useSynonyms) {

        for (String pattern : suitableMappings) {

            queries.addAll(Utilities.fillTemplates(pattern, tripleGenerator));
        }

        return queries;
    }

    private List<String[]>getbestResourcesByLevenstheinRatio(String coOccurrence,String index,HashMap<String,Double>minScores){
        List<String[]> foundResources = entityIndex.search(coOccurrence, index);
        double minScore=0.4;
        List<String[]>bestResourcesByLevenstheinRatio=new ArrayList<>();
        for(String[]resource:foundResources){
            double ratio = Utilities.getLevenshteinRatio(coOccurrence, resource[1].toLowerCase());
            if(ratio<minScore){
                minScore=ratio;
                bestResourcesByLevenstheinRatio.clear();
                bestResourcesByLevenstheinRatio.add(resource);
            }
            else if(ratio==minScore) bestResourcesByLevenstheinRatio.add(resource);
        }
        if(bestResourcesByLevenstheinRatio.size()>0) minScores.put(coOccurrence,minScore);
        return bestResourcesByLevenstheinRatio;
    }
    private String generateTypeFromResourceUriSubset(List<String[]>ambigiousResources) {
        Set<String>urisToTest=new HashSet();
        Random rand = new Random();
        Set<Integer>alreadySet=new HashSet<>();
        for (int i = 0; i < 10; i++) {
            boolean col=true;
            while(col) {
                int randomNumber = rand.nextInt(ambigiousResources.size());

                String[] res = ambigiousResources.get(rand.nextInt(ambigiousResources.size()));
                if(!urisToTest.contains(res[0])) {
                    urisToTest.add(res[0]);
                    col=false;
                }

            }

        }
        HashMap<String,Integer>types=new HashMap<>();
        for(String uri:urisToTest){
            List<Triple> triples=entityIndex.search(uri,"http://www.w3.org/1999/02/22-rdf-syntax-ns#type",null);
            for(Triple triple:triples){
                if(types.containsKey(triple.getObject())){
                    int value=types.get(triple.getObject())+1;
                    types.put(triple.getObject(),value);
                }
                else types.put(triple.getObject(),1);
            }
        }
        for(String type:types.keySet()){
            if(types.get(type)>8)return type;
        }
        return null;
    }
    private int rateCoOccurenceMappingWithOtherCoOccurenceConnections(String coOccurence,List<String>resourceUris,List<String>propertyUris,List<String>predicateUris){
        return 0;
    }
    private Set<String>generateEntityConnectionCandidates(String coOccurence,HashMap<String,String[]>coOccurenceEntityMap,HashMap<String,String>ambigiousResoorces){
        Set<String> entityCandidates=new HashSet<>();
        for(String key:coOccurenceEntityMap.keySet()){
            if(coOccurence.contains(key)&&!ambigiousResoorces.containsKey(key)&&!"entity".equals(ambigiousResoorces.get(key)))
                entityCandidates.add(coOccurenceEntityMap.get(key)[0]);

        }
        return entityCandidates;
    }
    private Set<String>getLinkedResources(String candidate){
        Set<String>linkedResources=new HashSet<>();
        entityIndex.search(candidate,null,null).forEach(triple->{linkedResources.add(triple.getObject());
        linkedResources.add(triple.getPredicate());});
        entityIndex.search(null,null,candidate).forEach(triple->{linkedResources.add(triple.getSubject());
        linkedResources.add(triple.getPredicate());});
        return linkedResources;
    }
    public double scoreCoOccurencesCandidateMappingByInterlinking(String candidate1,String candidate2,HashMap<String,Set<String>> linkingMap){
        if(!linkingMap.containsKey(candidate1)) linkingMap.put(candidate1,getLinkedResources(candidate1));
        if(!linkingMap.containsKey(candidate2)) linkingMap.put(candidate2,getLinkedResources(candidate2));
        if(linkingMap.get(candidate1).contains(candidate2)||linkingMap.get(candidate2).contains(candidate1))
            return 1.0;
        else return 0;
    }
    public double scoreCoOccurencesCandidateMappingByInterlinkingProperty(String property,String candidate,HashMap<String,Set<String>> linkingMap){
        if(!linkingMap.containsKey(candidate)) linkingMap.put(candidate,getLinkedResources(candidate));
        if(linkingMap.get(candidate).contains(property))
            return 1.0;
        else return 0;
    }
    private HashMap<String,String>generateMappings(Map<String,List<String[]>>candidateMapping, HashMap<String,Double>resourceToLinkingScore){
        HashMap<String,String>entityToQuestionMapping=new HashMap<>();
        HashMap<String,List<String[]>>skippedCoOccurences=new HashMap<>();
        for(String coOccurence : candidateMapping.keySet()){
            List<String[]> resources=candidateMapping.get(coOccurence);
            if(resources.size()==1) {
                if(!entitiyToQuestionMapping.containsKey(resources.get(0)[0])||entitiyToQuestionMapping.get(resources.get(0)[0]).length()<coOccurence.length())
                    entityToQuestionMapping.put(resources.get(0)[0], coOccurence);
            }
            else if(resources.size()>0){
                List<String>bestResourcesByLinkingScore=new ArrayList<>();
                double bestScore=0;
                for(String[]resource:resources){
                    if(resourceToLinkingScore.get(resource[0])>bestScore){
                        bestScore=resourceToLinkingScore.get(resource[0]);
                        bestResourcesByLinkingScore.clear();
                        bestResourcesByLinkingScore.add(resource[0]);

                    }
                    else if(resourceToLinkingScore.get(resource[0])==bestScore)
                        bestResourcesByLinkingScore.add(resource[0]);
                }
                if(bestResourcesByLinkingScore.size()==1){
                    if(!entitiyToQuestionMapping.containsKey(bestResourcesByLinkingScore.get(0))||entitiyToQuestionMapping.get(bestResourcesByLinkingScore.get(0)).length()<coOccurence.length())
                        entityToQuestionMapping.put(bestResourcesByLinkingScore.get(0),coOccurence);
                }
                //else if(bestResourcesByLinkingScore.size()>1&&bestScore>0) skippedCoOccurences.put(coOccurence,resources);
                else if(bestResourcesByLinkingScore.size()>1) skippedCoOccurences.put(coOccurence,resources);
            }
        }
        for(String coOccurence:skippedCoOccurences.keySet()){
            boolean contains=false;
            Iterator<String> i=entityToQuestionMapping.values().iterator();
            while (!contains&&i.hasNext()){
                String mappedCoOccurence=i.next();
                if(mappedCoOccurence.contains(coOccurence)){
                    contains=true;
                }
            }
            if(!contains){
                for(String key:entityToQuestionMapping.keySet()){
                    for(String[]candidate:skippedCoOccurences.get(coOccurence)){
                        if(candidate[0].equals(key))
                            contains=true;
                    }
                }
            }
            //if(!contains) entityToQuestionMapping.put(skippedCoOccurences.get(coOccurence).get(0)[0],coOccurence);
            if(!contains) skippedCoOccurences.get(coOccurence).forEach(s->entityToQuestionMapping.put(s[0],coOccurence));
        }
        return entityToQuestionMapping;
    }
    private List<String[]>disambiguateHighlyAmbigResources(String ambiqueCoOccurences,String type,
                                                           Set<String>entResources,Set<String>propResources,
                                                           double minScore,HashMap<String,Set<String>> linkingMap){

        List<String[]>candidateMappings=new ArrayList<>();
        Set<String>alreadyknown=new HashSet<>();
        for(String resource:entResources){
            List<String[]>connResources=relationsGenerator.getRelatedResourcesByType(resource, type);
            for(String[]res:connResources){
                alreadyknown.add(res[0]);
            }
            candidateMappings.addAll(connResources);
        }
        Set<String>resourcesToMap=new HashSet<>();
        for(String entResource:entResources){
            if(!linkingMap.containsKey(entResource)){
                linkingMap.put(entResource,getLinkedResources(entResource));
                resourcesToMap.addAll(linkingMap.get(entResource));
            }
        }
        for (String property : propResources) {
            List<String[]>connResources=relationsGenerator.getRelatedResourcesByTypeProperty(property, type);
            for(String[]res:connResources){
                if(resourcesToMap.contains(res[0])&&!alreadyknown.contains(res[0]))
                    candidateMappings.add(res);
            }
        }

        System.out.println();
        List<String[]>bestResourcesByLevenstheinRatio=new ArrayList<>();
        boolean modified=false;
        for(String[]resource:candidateMappings){
            double ratio = Utilities.getLevenshteinRatio(ambiqueCoOccurences, resource[1].toLowerCase());
            if(ratio<minScore){
                modified=true;
                minScore=ratio;
                bestResourcesByLevenstheinRatio.clear();
                bestResourcesByLevenstheinRatio.add(resource);
            }
            else if(ratio==minScore) {
                if(!modified){
                    modified=true;
                    minScore=ratio;
                    bestResourcesByLevenstheinRatio.clear();
                    bestResourcesByLevenstheinRatio.add(resource);
                }
                else bestResourcesByLevenstheinRatio.add(resource);
            }
        }
        //if(bestResourcesByLevenstheinRatio.size()>0) minScores.put(coOccurrence,minScore);
        return bestResourcesByLevenstheinRatio;
    }
    Map<String, String> extractEntities(String question) {
        //Map<String, List<String[]>> coOccurenceToentitiyCandidateMapping = new HashMap<>();
        //Map<String, List<String[]>> coOccurenceTopropertyCandidateMapping = new HashMap<>();
        //Map<String, List<String[]>> coOccurenceToclassCandidateMapping = new HashMap<>();
        question = semanticAnalysisHelper.removeQuestionWords(question);
        Map<String,List<String[]>>bestCoOccurencesToEntitieMappingsByLevensthein=new HashMap<>();
        Map<String,List<String[]>>bestCoOccurencesToPropertyMappingsByLevensthein=new HashMap<>();
        Map<String,List<String[]>>bestCoOccurencesToClassMappingsByLevensthein=new HashMap<>();
        HashMap<String,String>highlyAmbiqueCoOccurences=new HashMap<>();
        HashMap<String,Double>minScoresEntities=new HashMap<>();
        HashMap<String,Double>minScoresPredicates=new HashMap<>();
        HashMap<String,Double>minScoresClasses=new HashMap<>();
        //List<String> wordsFromQuestion = Arrays.asList(question.split(NON_WORD_CHARACTERS_REGEX));
        List<String> wordsFromQuestion=wordsGenerator.generateTokens(question,"de");
        List<String> coOccurrences = getNeighborCoOccurrencePermutations(wordsFromQuestion);
        for(String coOccurrence:coOccurrences) {
            List<String[]>best=getbestResourcesByLevenstheinRatio(coOccurrence,"entity",minScoresEntities);
            if(best.size()>0)
                bestCoOccurencesToEntitieMappingsByLevensthein.put(coOccurrence,best);
            if(best.size()>50) highlyAmbiqueCoOccurences.put(coOccurrence,"entity");
            best=getbestResourcesByLevenstheinRatio(coOccurrence,"property",minScoresPredicates);
            if(best.size()>0)
                bestCoOccurencesToPropertyMappingsByLevensthein.put(coOccurrence,best);
            if(best.size()>50) highlyAmbiqueCoOccurences.put(coOccurrence,"property");
            best=getbestResourcesByLevenstheinRatio(coOccurrence,"class",minScoresClasses);
            if(best.size()>0)
                bestCoOccurencesToClassMappingsByLevensthein.put(coOccurrence,best);
            if(best.size()>50) highlyAmbiqueCoOccurences.put(coOccurrence,"class");
        }
        Set<String>entResources=new HashSet<>();
        for(String coOccurence:bestCoOccurencesToEntitieMappingsByLevensthein.keySet()){
            bestCoOccurencesToEntitieMappingsByLevensthein.get(coOccurence).forEach(s->{if(!highlyAmbiqueCoOccurences.containsKey(coOccurence))entResources.add(s[0]);});
        }
        Set<String>propResources=new HashSet<>();
        for(String coOccurence:bestCoOccurencesToPropertyMappingsByLevensthein.keySet()){
            bestCoOccurencesToPropertyMappingsByLevensthein.get(coOccurence).forEach(s->{if(!highlyAmbiqueCoOccurences.containsKey(coOccurence))propResources.add(s[0]);});
        }
        HashMap<String,Set<String>>alreadyScored=new HashMap<>();
        //Score Entity to Entity
        HashMap<String,Set<String>>linkingMap=new HashMap<>();

        List<String>ambResources=new ArrayList<>();
        highlyAmbiqueCoOccurences.keySet().forEach(res->{if(highlyAmbiqueCoOccurences.get(res).equals("entity"))ambResources.add(res);});
        for(String ambResource:ambResources) {
            String type=generateTypeFromResourceUriSubset( bestCoOccurencesToEntitieMappingsByLevensthein.get(ambResource));
            if(type!=null) {
                List<String[]> best = disambiguateHighlyAmbigResources(ambResource, type, entResources,propResources, minScoresEntities.get(ambResource),linkingMap);
                if (best.size() > 0) {
                    bestCoOccurencesToEntitieMappingsByLevensthein.put(ambResource, best);
                    highlyAmbiqueCoOccurences.remove(ambResource);
                }
                else bestCoOccurencesToEntitieMappingsByLevensthein.remove(ambResource);
            }
            else System.out.println();
        }
        HashMap<String,Double>resourceToLinkingScore=new HashMap<>();
        for(String coOccurence:bestCoOccurencesToEntitieMappingsByLevensthein.keySet()){
            bestCoOccurencesToEntitieMappingsByLevensthein.get(coOccurence).forEach(s->{if(!highlyAmbiqueCoOccurences.containsKey(coOccurence))resourceToLinkingScore.put(s[0],0.0);});
        }
        for(String coOccurence1:bestCoOccurencesToEntitieMappingsByLevensthein.keySet()) {
            for (String coOccurence2 : bestCoOccurencesToEntitieMappingsByLevensthein.keySet()){
                if(!coOccurence1.contains(coOccurence2)&&!coOccurence2.contains(coOccurence1)
                &&!highlyAmbiqueCoOccurences.containsKey(coOccurence1)&&!highlyAmbiqueCoOccurences.containsKey(coOccurence2)){
                    for(String[]candidate1:bestCoOccurencesToEntitieMappingsByLevensthein.get(coOccurence1)){
                        for(String[]candidate2:bestCoOccurencesToEntitieMappingsByLevensthein.get(coOccurence2)){
                            if(!alreadyScored.containsKey(candidate1[0])||!alreadyScored.get(candidate1[0]).contains(candidate2[0])){
                                double score=scoreCoOccurencesCandidateMappingByInterlinking(candidate1[0],candidate2[0],linkingMap);
                                if(!alreadyScored.containsKey(candidate1[0]))alreadyScored.put(candidate1[0],new HashSet<String>());
                                if(!alreadyScored.containsKey(candidate2[0]))alreadyScored.put(candidate2[0],new HashSet<String>());
                                if(alreadyScored.containsKey(candidate1[0]))alreadyScored.get(candidate1[0]).add(candidate2[0]);
                                if(alreadyScored.containsKey(candidate2[0]))alreadyScored.get(candidate2[0]).add(candidate1[0]);
                                resourceToLinkingScore.put(candidate1[0],resourceToLinkingScore.get(candidate1[0])+score);
                                resourceToLinkingScore.put(candidate2[0],resourceToLinkingScore.get(candidate2[0])+score);
                            }
                        }
                    }
                }
            }
        }
        HashMap<String,Double>propertyToLinkingScore=new HashMap<>();
        for(String coOccurence:bestCoOccurencesToPropertyMappingsByLevensthein.keySet()){
            bestCoOccurencesToPropertyMappingsByLevensthein.get(coOccurence).forEach(s->{if(!highlyAmbiqueCoOccurences.containsKey(coOccurence))propertyToLinkingScore.put(s[0],0.0);});
        }
        for(String coOccurence1:bestCoOccurencesToPropertyMappingsByLevensthein.keySet()) {
            for (String coOccurence2 : bestCoOccurencesToEntitieMappingsByLevensthein.keySet()){
                if(!coOccurence1.contains(coOccurence2)&&!coOccurence2.contains(coOccurence1)
                        &&!highlyAmbiqueCoOccurences.containsKey(coOccurence1)&&!highlyAmbiqueCoOccurences.containsKey(coOccurence2)){
                    for(String[]candidate1:bestCoOccurencesToPropertyMappingsByLevensthein.get(coOccurence1)){
                        for(String[]candidate2:bestCoOccurencesToEntitieMappingsByLevensthein.get(coOccurence2)){
                            if(!alreadyScored.containsKey(candidate1[0])||!alreadyScored.get(candidate1[0]).contains(candidate2[0])){
                                double score=scoreCoOccurencesCandidateMappingByInterlinkingProperty(candidate1[0],candidate2[0],linkingMap);
                                if(!alreadyScored.containsKey(candidate1[0]))alreadyScored.put(candidate1[0],new HashSet<String>());
                                if(!alreadyScored.containsKey(candidate2[0]))alreadyScored.put(candidate2[0],new HashSet<String>());
                                if(alreadyScored.containsKey(candidate1[0]))alreadyScored.get(candidate1[0]).add(candidate2[0]);
                                if(alreadyScored.containsKey(candidate2[0]))alreadyScored.get(candidate2[0]).add(candidate1[0]);
                                propertyToLinkingScore.put(candidate1[0],propertyToLinkingScore.get(candidate1[0])+score);
                                resourceToLinkingScore.put(candidate2[0],resourceToLinkingScore.get(candidate2[0])+score);
                            }
                        }
                    }
                }
            }
        }
        HashMap<String,String>entityToQuestionMapping=generateMappings(bestCoOccurencesToEntitieMappingsByLevensthein,resourceToLinkingScore);
        entityToQuestionMapping.putAll(generateMappings(bestCoOccurencesToPropertyMappingsByLevensthein,propertyToLinkingScore));
        for(String classCoOccurence:bestCoOccurencesToClassMappingsByLevensthein.keySet()){
            if(bestCoOccurencesToClassMappingsByLevensthein.get(classCoOccurence).size()>1){
                double maxScore=0.0;
                String bestResource=null;
                for(String[]classCandidate:bestCoOccurencesToClassMappingsByLevensthein.get(classCoOccurence)){
                    double score=0.0;
                    for(String key:linkingMap.keySet()){
                        if(linkingMap.get(key).contains(classCandidate[0]))
                            score++;
                    }
                    if(score>maxScore){
                        bestResource=classCandidate[0];
                        maxScore=score;
                    }
                }
                if(bestResource!=null)
                    entityToQuestionMapping.put(bestResource,classCoOccurence);
                else{
                    for(String[] coOccurrence:bestCoOccurencesToClassMappingsByLevensthein.get(classCoOccurence)){
                        entityToQuestionMapping.put(coOccurrence[0],classCoOccurence);
                    }
                }
            }
            else entityToQuestionMapping.put(bestCoOccurencesToClassMappingsByLevensthein.get(classCoOccurence).get(0)[0],classCoOccurence);

        }
        return entityToQuestionMapping;
    }



    Set<String> findResourcesInFullText(String s) {
        List<String> questionWords = Arrays.asList("list|give|show|who|when|were|what|why|whose|how|where|which|is|are|did|was|does|a".split("\\|"));
        Set<String> result = new HashSet<>();
        //SPARQLResultSet sparqlResultSet = SPARQLUtilities.executeSPARQLQuery(String.format("select distinct ?s { ?s ?p ?o. ?s <http://www.w3.org/2000/01/rdf-schema#label> ?l. filter(langmatches(lang(?l), 'en')) ?l <bif:contains> \"'%s'\" }", s));
        List<SPARQLResultSet> sparqlResultSets = SPARQLUtilities.executeSPARQLQuery(String.format(SPARQLUtilities.FULLTEXT_SEARCH_SPARQL, s.replace("'", "\\\\'")));
        List<String> resultSet = new ArrayList<>();
        sparqlResultSets.forEach(sparqlResultSet -> resultSet.addAll(sparqlResultSet.getResultSet()));
        resultSet.parallelStream().filter(s1 -> s1.startsWith("http://")).forEach(uri -> {
            //uri = SPARQLUtilities.getRedirect(uri);
            String[] split;
            if (uri.startsWith("http://dbpedia.org/resource/")) {
                split = uri.split("http://dbpedia.org/resource/");
            } else {
                split = uri.split("/");
            }
            String resourceName = split[split.length - 1];
            if (!questionWords.contains(resourceName.toLowerCase())) {
                double levenshteinRatio = Utilities.getLevenshteinRatio(s.toLowerCase(), resourceName.replace("_", " ")
                        .replace("(", " ")
                        .replace(")", " ")
                        .replaceAll("\\s+", " ")
                        .trim()
                        .toLowerCase());
                if (levenshteinRatio < 0.2) {
                    result.add(uri);
                }
            }
        });
        return result;
    }

    private boolean existsAsEntity(String s) {
        List<SPARQLResultSet> sparqlResultSets = SPARQLUtilities.executeSPARQLQuery(String.format("ASK { VALUES (?r) {(<%s>)} {?r ?p ?o} UNION {?s ?r ?o} UNION {?s ?p ?r} }", s));
        return Boolean.valueOf(sparqlResultSets.get(0).getResultSet().get(0));
    }

    /*private Map<String, String> findResourcesBySynonyms(String question) {
        Map<String, String> rdfResources = new HashMap<>();

        List<String> coOccurrences = getNeighborCoOccurrencePermutations(Arrays.asList(question.split(NON_WORD_CHARACTERS_REGEX)));
        coOccurrences.parallelStream().forEach(coOccurrence -> {

            Set<String> puttyEntities = Sets.newHashSet(patty_relations.search(coOccurrence));
            puttyEntities.forEach(s -> rdfResources.put("http://dbpedia.org/ontology/" + s, coOccurrence));
        });

        WordNetWrapper wordNet = new WordNetWrapper();
        Map<String, String> synonyms = wordNet.lookUpWords(question);
        synonyms.forEach((synonym, origin) -> {
            if (synonym.contains(" ")) {
                String[] words = synonym.split(NON_WORD_CHARACTERS_REGEX);
                String wordsJoined = joinCapitalizedLemmas(words, false, true);
                tryDBpediaResourceNamingCombinations(ontologyURIs, words, wordsJoined).forEach(s -> rdfResources.put(s, origin));
                tryDBpediaResourceNamingCombinations(ontologyURIs, words, joinCapitalizedLemmas(words, true, true)).forEach(s -> rdfResources.put(s, origin));
                searchInDBOIndex(Strings.join(words, " ")).forEach(s -> rdfResources.put(s, origin));
                Arrays.asList(words).forEach(s -> searchInDBOIndex(s).forEach(s1 -> rdfResources.put(s1, origin)));
            } else {
                searchInDBOIndex(synonym).forEach(s -> rdfResources.put(s, origin));
            }
        });
        return rdfResources;
    }*/

    private boolean isResource(String s) {
        return SPARQLUtilities.isResource(s);
    }

    private boolean isOntology(String s) {
        return s.startsWith("http://dbpedia.org/ontology/") || s.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#type") || s.startsWith("http://dbpedia.org/datatype/");
    }

    /*private Set<String> searchInDBOIndex(String coOccurrence) {
        DBOIndex dboIndex = new DBOIndex();
        //The DBOIndex Class throws a NullPointerException when you search for a number
        if (StringUtils.isNumeric(coOccurrence)) {
            return new HashSet<>();
        } else if (coOccurrence.length() < 2) {
            //Don't use words like 'a'
            return new HashSet<>();
        } else {
            List<String> search = dboIndex.search(coOccurrence);
            Set<String> resultsInDBOIndex = search.parallelStream()
                    .filter(s -> {
                        String[] split = s.split("/");
                        String baseResourceName = split[split.length - 1];
                        double ratio = getLevenshteinRatio(coOccurrence, baseResourceName);
                        return ratio <= 0.5;
                    })
                    .collect(Collectors.toSet());

            IndexDBO_classes indexDBO_classes = new IndexDBO_classes();
            List<String> indexDBO_classesSearch = indexDBO_classes.search(coOccurrence);
            Set<String> resultsInDBOIndexClass = getResultsInDBOIndexFilteredByRatio(coOccurrence, indexDBO_classesSearch);
            List<String> indexDBO_propertySearch = new ArrayList<>();
            if (!Stopwords.isStopword(coOccurrence)) {
                IndexDBO_properties indexDBO_properties = new IndexDBO_properties();
                indexDBO_propertySearch = indexDBO_properties.search(coOccurrence);
                try {
                    indexDBO_properties.close();
                } catch (NullPointerException e) {
                    log.error("NullPointerException when trying to close IndexDBO_properties!", e);
                }
            }
            Set<String> resultsInDBOIndexProperty = getResultsInDBOIndexFilteredByRatio(coOccurrence, indexDBO_propertySearch);

            resultsInDBOIndex.addAll(resultsInDBOIndexClass);
            resultsInDBOIndex.addAll(resultsInDBOIndexProperty);
            return resultsInDBOIndex;
        }
    }*/

    private Set<String> getResultsInDBOIndexFilteredByRatio(String coOccurrence, List<String> indexDBO_classesSearch) {
        return indexDBO_classesSearch.parallelStream()
                .filter(s -> {
                    String[] split = s.split("/");
                    String baseResourceName = split[split.length - 1];
                    double ratio = Utilities.getLevenshteinRatio(coOccurrence, baseResourceName);
                    //TODO instead of using string similarity use the shortest one (e.g. Television instead of TelevisionShow) if it exists
                    return ratio < 0.5;
                })
                .collect(Collectors.toSet());
    }

    private List<String> tryDBpediaResourceNamingCombinations(Set<String> ontologyURIs, String[] words, String lemmasJoined) {
        List<String> addToResult = new ArrayList<>();
        if (words.length > 1 && SPARQLUtilities.isDBpediaEntity(String.format("http://dbpedia.org/resource/%s", lemmasJoined))) {
            addToResult.add(String.format("http://dbpedia.org/resource/%s", String.join("_", words)));
        }
        if (words.length <= 3 && ontologyURIs.contains(String.format("http://dbpedia.org/ontology/%s", lemmasJoined))) {
            addToResult.add(String.format("http://dbpedia.org/ontology/%s", lemmasJoined));
        }
        return addToResult;
    }

    public Set<String> generateQueries(Map<String, QueryTemplateMapping> mappings, boolean useSynonyms) {
        return generateQueries(mappings, null, useSynonyms);
    }

    private String joinCapitalizedLemmas(String[] strings, boolean capitalizeFirstLetter, boolean useLemma) {
        final String[] result = {""};
        List<String> list = Arrays.asList(strings);
        list = list.parallelStream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (useLemma) {
            list.forEach(s -> result[0] += StringUtils.capitalize(new Sentence(s).lemma(0)));
        } else {
            list.forEach(s -> result[0] += StringUtils.capitalize(s));
        }
        //The first letter is lowercase sometimes
        if (capitalizeFirstLetter) {
            return StringUtils.capitalize(result[0]);
        } else {
            return StringUtils.uncapitalize(result[0]);
        }
    }

    Set<String> fillPatterns(Map<String, String> rdfResources, List<String> suitableMappings) {
        Set<String> sparqlQueries = new HashSet<>();
        Set<String> baseResources = rdfResources.keySet().parallelStream().map(SPARQLUtilities::getRedirect).collect(Collectors.toSet());

        for (String pattern : suitableMappings) {
            List<String> classResources = new ArrayList<>();
            List<String> propertyResources = new ArrayList<>();
            for (String resource : baseResources) {
                if (!resourceStartsLowercase(resource)) {
                    classResources.add(resource);
                } else if (resourceStartsLowercase(resource)) {
                    propertyResources.add(resource);
                }
            }

            sparqlQueries.add(Utilities.fillPattern(pattern, classResources, propertyResources));
        }
        return sparqlQueries;
    }

    private List<String> getSuitableMappings(Map<String, QueryTemplateMapping> mappings, int queryType, String graph) {
        List<QueryTemplateMapping> templatesForGraph = new ArrayList<>();
        if (graph == null) {
            templatesForGraph = new ArrayList<>(mappings.values());
        } else {
            QueryTemplateMapping mapping = mappings.get(graph);
            if (mapping != null) {
                templatesForGraph.add(mapping);
            }
        }
        List<String> result = new ArrayList<>();
        if (queryType == SPARQLUtilities.SELECT_SUPERLATIVE_ASC_QUERY) {
            result = templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getSelectSuperlativeAscTemplate)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList());
        } else if (queryType == SPARQLUtilities.SELECT_SUPERLATIVE_DESC_QUERY) {
            result = templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getSelectSuperlativeDescTemplate)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList());
        } else if (queryType == SPARQLUtilities.SELECT_COUNT_QUERY) {
            result = templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getSelectCountTemplates)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList());
        } else if (queryType == SPARQLUtilities.ASK_QUERY) {
            result = templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getAskTemplates)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList());

            //templatesForGraph.forEach(queryTemplateMapping -> result.addAll(queryTemplateMapping.getAskTemplates()));
        }

        if (queryType == SPARQLUtilities.SELECT_QUERY) {
            result = templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getSelectTemplates)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList());

            //templatesForGraph.forEach(queryTemplateMapping -> result.addAll(queryTemplateMapping.getSelectTemplates()));
        }/* else {
            result.addAll(templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getAskTemplates)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList()));
            result.addAll(templatesForGraph.parallelStream()
                    .map(QueryTemplateMapping::getSelectTemplates)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(Collectors.toList()));
                */
            // templatesForGraph.forEach(queryTemplateMapping -> result.addAll(queryTemplateMapping.getAskTemplates()));
            // templatesForGraph.forEach(queryTemplateMapping -> result.addAll(queryTemplateMapping.getSelectTemplates()));
        //}
        return result;
    }

    /*Map<String, String> getProperties(String words) {
        if (words.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> result = new HashMap<>();
        final String relevantPos = "JJ.*|NN.*|VB.*";
        Annotation document = new Annotation(words);
        StanfordCoreNLP pipeline = StanfordPipelineProvider.getSingletonPipelineInstance();
        pipeline.annotate(document);
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                String pos = token.get(PartOfSpeechAnnotation.class);
                String lemma = token.get(LemmaAnnotation.class);
                if (pos.matches(relevantPos) && !Stopwords.isStopword(lemma)) {
                    Set<String> matchingLemmaProperties = properties.parallelStream()
                            .filter(property -> property.equalsIgnoreCase(String.format("http://dbpedia.org/property/%s", lemma)))
                            .collect(Collectors.toSet());
                    matchingLemmaProperties.forEach(s -> result.put(s, token.value()));
                    Set<String> matchingProperties = properties.parallelStream()
                            .filter(property -> property.equalsIgnoreCase(String.format("http://dbpedia.org/property/%s", words)))
                            .collect(Collectors.toSet());
                    matchingProperties.forEach(s -> result.put(s, token.value()));
                }
            }
        }

        if (this.queryType == SPARQLUtilities.SELECT_COUNT_QUERY || this.queryType == SPARQLUtilities.SELECT_QUERY) {
            Map<String, String> pos = SemanticAnalysisHelper.getPOS(words);
            Set<String> nouns = pos.keySet().parallelStream().filter(s -> pos.get(s).startsWith("NN")).collect(Collectors.toSet());
            nouns.parallelStream().forEach(s -> {
                String propertyCandidate = "http://dbpedia.org/property/" + s + "Total";
                if (properties.contains(propertyCandidate)) {
                    result.put(propertyCandidate, s);
                }
            });
        }

        List<String> coOccurrences = getNeighborCoOccurrencePermutations(Arrays.asList(words.split(NON_WORD_CHARACTERS_REGEX)));
        coOccurrences.parallelStream().forEach(coOccurrence -> {
            if (!Stopwords.isStopword(coOccurrence)) {
                String propertyCandidate = "http://dbpedia.org/property/" + joinCapitalizedLemmas(coOccurrence.split(NON_WORD_CHARACTERS_REGEX), false, false);
                if (properties.contains(propertyCandidate)) {
                    result.put(propertyCandidate, coOccurrence);
                }
            }
        });
        return result;
    }*/

    /*Map<String, String> getOntologyClass(String words) {
        if (words.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> result = new HashMap<>();
        final String relevantPos = "JJ.*|NN.*|VB.*";
        Annotation document = new Annotation(words);
        StanfordCoreNLP pipeline = StanfordPipelineProvider.getSingletonPipelineInstance();
        pipeline.annotate(document);
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                String pos = token.get(PartOfSpeechAnnotation.class);
                String lemma = token.get(LemmaAnnotation.class);
                if (pos.matches(relevantPos)) {
                    Set<String> matchingClasses = ontologyNodes.parallelStream()
                            .filter(rdfNode -> rdfNode.toString().equalsIgnoreCase(String.format("http://dbpedia.org/ontology/%s", lemma)))
                            .map(RDFNode::toString)
                            .collect(Collectors.toSet());
                    matchingClasses.forEach(s -> result.put(s, token.value()));
                }
            }
        }

        if (this.queryType == SPARQLUtilities.SELECT_COUNT_QUERY) {
            Map<String, String> pos = SemanticAnalysisHelper.getPOS(words);
            Set<String> nouns = pos.keySet().parallelStream().filter(s -> pos.get(s).startsWith("NN")).collect(Collectors.toSet());
            nouns.parallelStream().forEach(s -> {
                String propertyCandidateLowercase = "http://dbpedia.org/ontology/" + s + "Total";
                Set<String> ontologyClassCandidates = ontologyNodes.parallelStream()
                        .filter(rdfNode -> rdfNode.toString().equals(propertyCandidateLowercase))
                        .map(RDFNode::toString)
                        .collect(Collectors.toSet());

                String propertyCandidateUppercase = "http://dbpedia.org/ontology/" + StringUtils.capitalize(s) + "Total";
                ontologyClassCandidates.addAll(ontologyNodes.parallelStream()
                        .filter(rdfNode -> rdfNode.toString().equals(propertyCandidateUppercase))
                        .map(RDFNode::toString)
                        .collect(Collectors.toSet()));
                ontologyClassCandidates.forEach(matchingClass -> result.put(matchingClass, s));
            });
        }

        String[] wordsSplitted = words.split(NON_WORD_CHARACTERS_REGEX);
        String lowercaseCandidate = "http://dbpedia.org/ontology/" + joinCapitalizedLemmas(wordsSplitted, false, false);
        if (ontologyNodes.parallelStream().anyMatch(rdfNode -> rdfNode.toString().equals(lowercaseCandidate))) {
            result.put(lowercaseCandidate, words);
        }
        String uppercaseCandidate = "http://dbpedia.org/ontology/" + joinCapitalizedLemmas(wordsSplitted, true, false);
        if (ontologyNodes.parallelStream().anyMatch(rdfNode -> rdfNode.toString().equals(uppercaseCandidate))) {
            result.put(uppercaseCandidate, words);
        }

        String lowercaseLemmaCandidate = "http://dbpedia.org/ontology/" + joinCapitalizedLemmas(wordsSplitted, false, true);
        if (ontologyNodes.parallelStream().anyMatch(rdfNode -> rdfNode.toString().equals(lowercaseLemmaCandidate))) {
            result.put(lowercaseLemmaCandidate, words);
        }
        String uppercaseLemmaCandidate = "http://dbpedia.org/ontology/" + joinCapitalizedLemmas(wordsSplitted, true, true);
        if (ontologyNodes.parallelStream().anyMatch(rdfNode -> rdfNode.toString().equals(uppercaseLemmaCandidate))) {
            result.put(uppercaseLemmaCandidate, words);
        }

        return result;
    }*/

    private boolean resourceStartsLowercase(String rdfResource) {
        return (rdfResource.startsWith("http://dbpedia.org/property/")
                || rdfResource.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || (rdfResource.contains("/") && rdfResource.charAt(rdfResource.length() - 1) != '/' && Character.isLowerCase(rdfResource.substring(rdfResource.lastIndexOf('/') + 1).charAt(0))))
                &&!rdfResource.equals("http://linkedgeodata.org/vocabulary#platform")
                &&!rdfResource.startsWith("https://portal.limbo-project.org/traffic-lights");
    }

    public Map<String, String> getEntitiyToQuestionMapping() {
        return entitiyToQuestionMapping;
    }
}


