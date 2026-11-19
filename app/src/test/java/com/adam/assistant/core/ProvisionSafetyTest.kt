package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionSafetyTest {

    @Test
    fun acceptsOrdinaryEntries() {
        assertNull(ProvisionSafety.entryRejection("sherpa-onnx/encoder.onnx"))
        assertNull(ProvisionSafety.entryRejection("top/sub/file.txt"))
    }

    @Test
    fun rejectsAbsoluteTraversalSymlinkAndSpecial() {
        assertTrue(ProvisionSafety.entryRejection("/etc/passwd")!!.startsWith("absolute"))
        assertTrue(
            ProvisionSafety.entryRejection("models/../../secret")!!.startsWith("traversal")
        )
        assertTrue(
            ProvisionSafety.entryRejection("..")!!.startsWith("traversal")
        )
        assertTrue(
            ProvisionSafety.entryRejection("top/link", isSymlink = true)!!.startsWith("symbolic")
        )
        assertTrue(
            ProvisionSafety.entryRejection("top/dev", isSpecial = true)!!.startsWith("special")
        )
        assertTrue(ProvisionSafety.entryRejection("") != null)
        assertTrue(ProvisionSafety.entryRejection(null) != null)
    }

    @Test
    fun descendantCheckIsComponentNotStringPrefix() {
        // The classic bug: siblings that share a string prefix must NOT pass.
        assertTrue(ProvisionSafety.isDescendantOf("/data/models", "/data"))
        assertTrue(ProvisionSafety.isDescendantOf("/data/models/x", "/data/models"))
        assertTrue(ProvisionSafety.isDescendantOf("/data/models", "/data/models"))
        assertFalse(
            ProvisionSafety.isDescendantOf("/data/models-evil", "/data/models")
        )
        assertFalse(ProvisionSafety.isDescendantOf("/database", "/data"))
        assertFalse(ProvisionSafety.isDescendantOf("/other/deep/path", "/data/models"))
    }

    @Test
    fun stripsOneLeadingComponent() {
        assertEquals("encoder.onnx", ProvisionSafety.stripLeadingComponent("top/encoder.onnx"))
        assertEquals("sub/file.txt", ProvisionSafety.stripLeadingComponent("top/sub/file.txt"))
        assertEquals("", ProvisionSafety.stripLeadingComponent("top"))
        assertEquals("", ProvisionSafety.stripLeadingComponent("noSlash"))
        assertEquals("", ProvisionSafety.stripLeadingComponent(""))
    }
}
