package edu.uw.cse.sideeffect.output;

import edu.uw.cse.sideeffect.analysis.MethodSummary;
import java.util.List;


/**
 * Formats and prints side-effect verdicts to stdout.
 */
public class ResultPrinter {

    public static void print(List<MethodSummary> summaries) {
        if (summaries.isEmpty()) {
            System.out.println("No methods analyzed.");
            return;
        }

        System.out.println();
        System.out.println("=== Side-Effect Analysis Results ===");

        // Compute max signature length for alignment
        int maxLen = 0;
        for (MethodSummary s : summaries) {
            maxLen = Math.max(maxLen, formatSignature(s.getMethodSignature()).length());
        }

        for (MethodSummary s : summaries) {
            String sig = formatSignature(s.getMethodSignature());
            String padded = String.format("%-" + (maxLen + 2) + "s", sig);
            String verdict;
            switch (s.getResult()) {
                case SIDE_EFFECT_FREE:
                    verdict = "SIDE_EFFECT_FREE";
                    break;
                case GRAPH_VIOLATION:
                    verdict = "\033[31mGRAPH VIOLATION  (" + s.getReason() + ")\033[0m";
                    break;
                default:
                    verdict = "SIDE_EFFECTING  (" + s.getReason() + ")";
                    break;
            }
            System.out.println(padded + ": " + verdict);
        }
        System.out.println();
    }

    /**
     * Simplify the full SootUp method signature for display.
     * E.g., "<SideEffectFreeMethods: int add(int,int)>" -> "SideEffectFreeMethods.add(int,int)"
     */
    private static String formatSignature(String sig) {
        // SootUp signatures look like: <ClassName: ReturnType methodName(ParamTypes)>
        String s = sig;
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1);
        }
        // Split on ": "
        int colonIdx = s.indexOf(": ");
        if (colonIdx < 0) return sig;

        String className = s.substring(0, colonIdx);
        String rest = s.substring(colonIdx + 2); // "ReturnType methodName(ParamTypes)"

        // Find method name and params — skip return type
        int parenIdx = rest.indexOf('(');
        if (parenIdx < 0) return sig;

        String beforeParen = rest.substring(0, parenIdx);
        String params = rest.substring(parenIdx);

        // beforeParen is "ReturnType methodName" — get the last token
        int lastSpace = beforeParen.lastIndexOf(' ');
        String methodName = lastSpace >= 0 ? beforeParen.substring(lastSpace + 1) : beforeParen;

        return className + "." + methodName + params;
    }
}
