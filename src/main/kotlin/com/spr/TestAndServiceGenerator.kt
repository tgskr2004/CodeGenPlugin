package com.spr

object ServiceAndTestGenerator {

    fun generateServiceClass(dtoClassName: String): Pair<String, String> {
        val baseName = dtoClassName.removeSuffix("Dto")
        val className = "${baseName}Service"

        val content = """
        public class $className {

            public String toStringSummary($dtoClassName dto) {
                return dto.toString();
            }

            public boolean validate($dtoClassName dto) {
                return dto != null;
            }

            public boolean isEmpty($dtoClassName dto) {
                return dto.toString().isEmpty();
            }
        }
    """.trimIndent()

        return className to content
    }

    fun generateTestClass(dtoClassName: String): Pair<String, String> {
        val baseName = dtoClassName.removeSuffix("Dto")
        val serviceClass = "${baseName}Service"
        val testClassName = "${serviceClass}Test"

        val content = """
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;

        public class $testClassName {

            @Test
            public void testToStringSummary() {
                $dtoClassName dto = new $dtoClassName();
                $serviceClass service = new $serviceClass();
                String summary = service.toStringSummary(dto);
                assertNotNull(summary);
            }

            @Test
            public void testValidate() {
                $dtoClassName dto = new $dtoClassName();
                $serviceClass service = new $serviceClass();
                assertTrue(service.validate(dto));
            }

            @Test
            public void testIsEmpty() {
                $dtoClassName dto = new $dtoClassName();
                $serviceClass service = new $serviceClass();
                assertNotNull(service.isEmpty(dto)); // just a sample
            }
        }
    """.trimIndent()

        return testClassName to content
    }

}
