-- FR-AGT-TPL-008: template_project.template_type column for MCP TPL tool domain
ALTER TABLE template_project ADD COLUMN template_type VARCHAR(50) DEFAULT 'REACT';
