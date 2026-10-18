import com.sulkan.shaders.graph.*;

/** CPU schema/schedule/allocation contract, separate from real Vulkan execution. */
public class DisplayInputsContract {
 private static final String JSON="""
 {"version":1,"name":"display contract","resources":{
 "color":{"format":"RGBA16_FLOAT"},"depth":{"format":"R32_FLOAT"},"motion":{"format":"RG16_FLOAT"},
 "exposure":{"format":"R32_FLOAT","size":[1,1,1]},"meter":{"format":"RGBA32_FLOAT","size":[1,1,1]},"scratch":{"format":"RGBA32_FLOAT","size":[1,1,1]}},
 "passes":[{"name":"meter","compute":"meter.comp","reads":{},"writes":["meter"]},
 {"name":"exposure","compute":"exposure.comp","reads":{"Meter":"meter"},"writes":["exposure"]},
 {"name":"scratch","compute":"scratch.comp","reads":{"E":"exposure"},"writes":["scratch"]},
 {"name":"base","compute":"base.comp","reads":{"Scratch":"scratch"},"writes":["color","depth","motion"]}],
 "present":"color","reconstruction":{"type":"dlss_quality","color":"color","depth":"depth","motion":"motion","exposure":"exposure","display":"display.fsh","displayReads":{"Meter":"meter"}}}
 """;
 public static void main(String[] args) {
  var graph=PackGraph.parse(JSON);var plan=GraphAllocations.plan(graph);
  if(!graph.reconstruction().displayReads().get("Meter").equals("meter"))throw new AssertionError("Missing display binding");
  if(plan.resourceSlots().get("meter").equals(plan.resourceSlots().get("scratch")))throw new AssertionError("Display input aliased before final presentation");
  var legacy=PackGraph.parse(JSON.replace(",\"displayReads\":{\"Meter\":\"meter\"}",""));
  if(!legacy.reconstruction().displayReads().isEmpty())throw new AssertionError("Legacy pack gained display bindings");
  reject(JSON.replace("\"displayReads\":{\"Meter\":\"meter\"}","\"displayReads\":{\"Source\":\"meter\"}"));
  reject(JSON.replace("\"displayReads\":{\"Meter\":\"meter\"}","\"displayReads\":{\"Meter\":\"missing\"}"));
  reject(JSON.replace("\"displayReads\":{\"Meter\":\"meter\"}","\"displayReads\":{\"Meter\":\"meter@previous\"}"));
  reject(JSON.replace("RGBA32_FLOAT","RGBA32_UINT"));
  System.out.println("SULKAN_DISPLAY_INPUTS_CONTRACT_PASS legacy=true lifetime=true invalidBindingsRejected=true scope=CPU_ONLY");
 }
 private static void reject(String json){try{PackGraph.parse(json).schedule();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Accepted invalid display bindings");}
}
